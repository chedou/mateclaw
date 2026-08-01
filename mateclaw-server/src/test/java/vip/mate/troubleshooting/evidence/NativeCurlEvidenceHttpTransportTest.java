package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeCurlEvidenceHttpTransportTest {

    @Test
    void passesSecretsThroughStdinConfigAndNeverThroughTheProcessArguments() throws Exception {
        AtomicReference<List<String>> command = new AtomicReference<>();
        FakeProcess process = new FakeProcess(
                "{\"code\":200,\"success\":true}\n"
                        + "__MATECLAW_HTTP_STATUS__:200",
                0);
        NativeCurlEvidenceHttpTransport transport = new NativeCurlEvidenceHttpTransport(
                "/usr/bin/curl",
                arguments -> {
                    command.set(List.copyOf(arguments));
                    return process;
                });

        EvidenceHttpTransport.Response response = transport.postJson(
                URI.create("http://guance.example/api/v1/df/query_data_v1"),
                Map.of(
                        "Content-Type", "application/json",
                        "DF-API-KEY", "runtime-secret"),
                "{\"queries\":[{\"q\":\"show_logging_source()\"}]}",
                Duration.ofSeconds(15));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"success\":true");
        assertThat(command.get()).containsExactly("/usr/bin/curl", "-q", "--config", "-");
        assertThat(String.join(" ", command.get())).doesNotContain("runtime-secret");

        String config = process.stdin(StandardCharsets.UTF_8);
        assertThat(config)
                .contains("header = \"DF-API-KEY: runtime-secret\"")
                .contains("data-binary = \"{\\\"queries\\\"")
                .contains("write-out = \"\\n__MATECLAW_HTTP_STATUS__:%{http_code}\"");
    }

    @Test
    void rejectsHeaderInjectionBeforeStartingCurl() {
        NativeCurlEvidenceHttpTransport transport = new NativeCurlEvidenceHttpTransport(
                "/usr/bin/curl",
                ignored -> {
                    throw new AssertionError("curl must not start for an unsafe header");
                });

        assertThatThrownBy(() -> transport.postJson(
                URI.create("http://guance.example/api/v1/df/query_data_v1"),
                Map.of("DF-API-KEY", "secret\r\nInjected: true"),
                "{}",
                Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header");
    }

    @Test
    void failsWithoutReturningCurlStderrOrCredentialMaterial() {
        NativeCurlEvidenceHttpTransport transport = new NativeCurlEvidenceHttpTransport(
                "/usr/bin/curl",
                ignored -> new FakeProcess("", "upstream mentions runtime-secret", 7));

        assertThatThrownBy(() -> transport.postJson(
                URI.create("http://guance.example/api/v1/df/query_data_v1"),
                Map.of("DF-API-KEY", "runtime-secret"),
                "{}",
                Duration.ofSeconds(5)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("exit code 7")
                .hasMessageNotContaining("runtime-secret")
                .hasMessageNotContaining("upstream mentions");
    }

    private static final class FakeProcess extends Process {
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;

        private FakeProcess(String stdout, int exitCode) {
            this(stdout, "", exitCode);
        }

        private FakeProcess(String stdout, String stderr, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        private String stdin(java.nio.charset.Charset charset) {
            return stdin.toString(charset);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }
    }
}
