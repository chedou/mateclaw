package vip.mate.troubleshooting.demo;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Test-only demo-scenario switch used by acceptance checks.
 *
 * <p>Kept separate from evidence configuration so that "there is a walkable
 * path" and "a real observability source is trusted" stay two different
 * decisions. Turning this on seeds a fixture-backed Playbook; it does not
 * enable any real source and does not change {@code fixtureMode}.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "mateclaw.troubleshooting.demo")
public class TroubleshootingDemoProperties {

    /** Explicit opt-in; production deployments leave this false. */
    private boolean enabled;

    /** Workspace the demo playbook is seeded into. */
    private long workspaceId = 1L;

    // The repository's test compilation currently does not run Lombok's
    // getter generation for this test-only configuration class.
    public long getWorkspaceId() {
        return workspaceId;
    }
}
