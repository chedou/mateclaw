package vip.mate.troubleshooting.evidence;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Small injected port that keeps third-party HTTP outside evidence normalization tests. */
interface EvidenceHttpTransport {

    Response postJson(
            URI uri,
            Map<String, String> headers,
            String body,
            Duration timeout) throws Exception;

    record Response(int statusCode, String body) {
        public Response {
            body = body == null ? "" : body;
        }
    }
}
