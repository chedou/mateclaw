package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.SopEntry;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical SHA-256 identity for exact candidates and exact bundled suites. */
@Component
public final class ManualPlaybookReplayFingerprint {

    private final ObjectMapper canonicalMapper;

    public ManualPlaybookReplayFingerprint(ObjectMapper objectMapper) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String candidate(SopEntry candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        return fingerprint(candidate);
    }

    public String suite(ManualPlaybookReplaySuite suite) {
        if (suite == null) {
            throw new IllegalArgumentException("replay suite is required");
        }
        return fingerprint(suite);
    }

    private String fingerprint(Object value) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new MateClawException(
                    "err.troubleshooting.manual_replay_fingerprint_failed",
                    500,
                    "manual Playbook replay identity cannot be calculated");
        }
    }
}
