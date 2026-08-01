package vip.mate.troubleshooting.evidence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Explicit native-curl compatibility transport for local evidence pilots.
 *
 * <p>The complete curl configuration is sent through stdin. Neither credentials
 * nor request bodies are placed in process arguments, environment variables, or
 * temporary files. The default evidence transport remains the JDK client.</p>
 */
final class NativeCurlEvidenceHttpTransport implements EvidenceHttpTransport {

    private static final Pattern SAFE_HEADER_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");
    private static final String STATUS_MARKER = "\n__MATECLAW_HTTP_STATUS__:";
    private static final int MAX_HEADER_VALUE_CHARS = 8 * 1024;
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 16 * 1024;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

    private final String executable;
    private final ProcessStarter processStarter;

    NativeCurlEvidenceHttpTransport(String executable) {
        this(executable, NativeCurlEvidenceHttpTransport::startProcess);
    }

    NativeCurlEvidenceHttpTransport(String executable, ProcessStarter processStarter) {
        this.executable = validateExecutable(executable);
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
    }

    @Override
    public Response postJson(
            URI uri,
            Map<String, String> headers,
            String body,
            Duration timeout) throws Exception {
        URI safeUri = validateUri(uri);
        Map<String, String> safeHeaders = validateHeaders(headers);
        String safeBody = validateBody(body);
        Duration safeTimeout = validateTimeout(timeout);

        // -q must be curl's first option so user-level .curlrc files are never loaded.
        Process process = processStarter.start(List.of(executable, "-q", "--config", "-"));
        try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> stdout = readers.submit(
                    () -> readBounded(process.getInputStream(), MAX_RESPONSE_BYTES));
            Future<byte[]> stderr = readers.submit(
                    () -> readBounded(process.getErrorStream(), MAX_STDERR_BYTES));

            try (var stdin = process.getOutputStream()) {
                stdin.write(curlConfig(safeUri, safeHeaders, safeBody, safeTimeout)
                        .getBytes(StandardCharsets.UTF_8));
            }

            boolean finished;
            try {
                finished = process.waitFor(safeTimeout.toMillis() + 1_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("native curl transport interrupted");
            }
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("native curl transport timed out");
            }

            int exitCode = process.exitValue();
            byte[] responseBytes = completed(stdout, "response");
            completed(stderr, "error stream");
            if (exitCode != 0) {
                throw new IOException(
                        "native curl transport failed with exit code " + exitCode);
            }
            return parseResponse(new String(responseBytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            throw failure;
        }
    }

    private static Process startProcess(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.redirectErrorStream(false);
        return builder.start();
    }

    private static String validateExecutable(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("native curl executable must be configured");
        }
        Path path = Path.of(candidate.trim());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("native curl executable must be absolute");
        }
        return path.normalize().toString();
    }

    private static URI validateUri(URI candidate) {
        Objects.requireNonNull(candidate, "uri");
        String scheme = candidate.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || candidate.getHost() == null
                || candidate.getUserInfo() != null
                || candidate.getFragment() != null) {
            throw new IllegalArgumentException("native curl URI must be a safe HTTP endpoint");
        }
        return candidate;
    }

    private static Map<String, String> validateHeaders(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers");
        headers.forEach((name, value) -> {
            if (name == null || !SAFE_HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("unsafe HTTP header name");
            }
            if (value == null
                    || value.length() > MAX_HEADER_VALUE_CHARS
                    || containsControlLineBreak(value)) {
                throw new IllegalArgumentException("unsafe HTTP header value");
            }
        });
        return Map.copyOf(headers);
    }

    private static String validateBody(String body) {
        Objects.requireNonNull(body, "body");
        if (body.indexOf('\0') >= 0
                || body.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("native curl request body is unsafe or too large");
        }
        return body;
    }

    private static Duration validateTimeout(Duration timeout) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("native curl timeout is invalid");
        }
        return timeout;
    }

    private static boolean containsControlLineBreak(String value) {
        return value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\0') >= 0;
    }

    private static String curlConfig(
            URI uri,
            Map<String, String> headers,
            String body,
            Duration timeout) {
        long timeoutSeconds = Math.max(1L, (timeout.toMillis() + 999L) / 1_000L);
        StringBuilder config = new StringBuilder(512 + body.length());
        config.append("silent\n")
                .append("show-error\n")
                .append("request = \"POST\"\n")
                .append("url = \"").append(escapeConfig(uri.toASCIIString())).append("\"\n");
        headers.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> config.append("header = \"")
                        .append(escapeConfig(entry.getKey()))
                        .append(": ")
                        .append(escapeConfig(entry.getValue()))
                        .append("\"\n"));
        config.append("data-binary = \"")
                .append(escapeConfig(body))
                .append("\"\n")
                .append("connect-timeout = 5\n")
                .append("max-time = ").append(timeoutSeconds).append('\n')
                .append("write-out = \"\\n__MATECLAW_HTTP_STATUS__:%{http_code}\"\n");
        return config.toString();
    }

    private static String escapeConfig(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                case '\0' -> throw new IllegalArgumentException("curl config contains NUL");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("native curl output exceeded its safety limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static byte[] completed(Future<byte[]> future, String stream) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("native curl " + stream + " read was interrupted");
        } catch (ExecutionException failure) {
            throw new IOException("native curl could not read its " + stream);
        }
    }

    private static Response parseResponse(String output) throws IOException {
        int marker = output.lastIndexOf(STATUS_MARKER);
        if (marker < 0) {
            throw new IOException("native curl response did not contain an HTTP status");
        }
        String status = output.substring(marker + STATUS_MARKER.length()).trim();
        if (!status.matches("[1-5][0-9]{2}")) {
            throw new IOException("native curl response contained an invalid HTTP status");
        }
        return new Response(Integer.parseInt(status), output.substring(0, marker));
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }
}
