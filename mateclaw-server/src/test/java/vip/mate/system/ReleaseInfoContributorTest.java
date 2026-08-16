package vip.mate.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

class ReleaseInfoContributorTest {

    @Test
    void publishesNormalizedGitCommit() {
        Info.Builder builder = new Info.Builder();
        new ReleaseInfoContributor(" A230FD63ABCDEF ").contribute(builder);

        assertThat(builder.build().getDetails())
                .containsEntry("release", java.util.Map.of("commit", "a230fd63abcdef"));
    }

    @Test
    void hidesInvalidDeploymentIdentity() {
        Info.Builder builder = new Info.Builder();
        new ReleaseInfoContributor("not-a-commit").contribute(builder);

        assertThat(builder.build().getDetails())
                .containsEntry("release", java.util.Map.of("commit", "unknown"));
    }
}
