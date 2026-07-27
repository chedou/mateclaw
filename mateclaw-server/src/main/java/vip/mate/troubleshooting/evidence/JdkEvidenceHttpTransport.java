package vip.mate.troubleshooting.evidence;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** JDK HTTP implementation used only by read-only evidence adapters. */
final class JdkEvidenceHttpTransport implements EvidenceHttpTransport {

    private final HttpClient httpClient;

    JdkEvidenceHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Response postJson(
            java.net.URI uri,
            Map<String, String> headers,
            String body,
            Duration timeout) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<String> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
