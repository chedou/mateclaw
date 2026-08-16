package vip.mate.system;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/** Publishes the immutable Git identity of the running deployment. */
@Component
public final class ReleaseInfoContributor implements InfoContributor {

    private final String commit;

    public ReleaseInfoContributor(
            @Value("${mateclaw.release.commit:unknown}") String commit) {
        String normalized = commit == null ? "" : commit.trim().toLowerCase();
        this.commit = normalized.matches("[0-9a-f]{7,40}") ? normalized : "unknown";
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("release", Map.of("commit", commit));
    }
}
