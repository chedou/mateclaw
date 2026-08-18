package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuanceRecordingBatchReadinessServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void acceptsOneWorkspaceBatchComposedOfTenTargetsFromEachOfTwoServices() {
        Fixture fixture = fixture(targets(false));

        GuanceRecordingBatchReadiness view = fixture.service().inspect(7L);

        assertThat(view.workspaceId()).isEqualTo(7L);
        assertThat(view.frozenTargetCount()).isEqualTo(20);
        assertThat(view.executableTargetCount()).isEqualTo(20);
        assertThat(view.readyForOwnerAcceptance()).isTrue();
        assertThat(view.blockers()).isEmpty();
        assertThat(view.targets())
                .filteredOn(target -> target.service().equals("service-a"))
                .hasSize(10);
        assertThat(view.targets())
                .filteredOn(target -> target.service().equals("service-b"))
                .hasSize(10);
        assertThat(view.targets())
                .allSatisfy(target -> {
                    assertThat(target.executable()).isTrue();
                    assertThat(target.scenarioKey()).isNull();
                    assertThat(target.bindingFingerprint()).matches("[a-f0-9]{64}");
                    assertThat(target.targetBindingFingerprint()).matches("[a-f0-9]{64}");
                    assertThat(target.blockers()).isEmpty();
                });
        assertThat(view.targets())
                .extracting(GuanceRecordingBatchReadiness.TargetReadiness::targetBindingFingerprint)
                .doesNotHaveDuplicates();
    }

    @Test
    void excludesOnlyTheTargetWhoseExactBindingNoLongerMatches() {
        Fixture fixture = fixture(targets(true));

        GuanceRecordingBatchReadiness view = fixture.service().inspect(7L);

        assertThat(view.frozenTargetCount()).isEqualTo(20);
        assertThat(view.executableTargetCount()).isEqualTo(19);
        assertThat(view.readyForOwnerAcceptance()).isFalse();
        assertThat(view.blockers())
                .contains("only 19 of 20 workspace recording targets are executable; 20 required");
        assertThat(view.targets())
                .filteredOn(target -> !target.executable())
                .singleElement()
                .satisfies(target -> {
                    assertThat(target.targetId()).isEqualTo("target-b-10");
                    assertThat(target.blockers())
                            .containsExactly("frozen target bindings do not match the running bindings");
                });
    }

    @Test
    void refusesToTreatMoreThanThirtyTargetsAsTheImmutableFirstBatch() {
        List<GuanceRecordingTargetCatalog.Target> oversized =
                new ArrayList<>(targets(false));
        for (int index = 11; index <= 21; index++) {
            oversized.add(target("a", index, bindings("a")));
        }

        GuanceRecordingBatchReadiness view = fixture(oversized).service().inspect(7L);

        assertThat(view.frozenTargetCount()).isEqualTo(31);
        assertThat(view.executableTargetCount()).isEqualTo(31);
        assertThat(view.readyForOwnerAcceptance()).isFalse();
        assertThat(view.blockers()).containsExactly(
                "workspace first recording batch contains 31 targets; at most 30 allowed");
    }

    @Test
    void excludesScenarioTargetUntilItsScenarioScopedBindingCanBeFingerprinted() {
        List<GuanceRecordingTargetCatalog.Target> mixed =
                new ArrayList<>(targets(false));
        GuanceRecordingTargetCatalog.Target original = mixed.getFirst();
        mixed.set(0, new GuanceRecordingTargetCatalog.Target(
                original.targetId(),
                original.system(),
                original.service(),
                "csdp:scenario:scenario-a-1",
                original.candidateReference(),
                original.candidateFingerprint(),
                original.requiredEvidenceRequestId(),
                original.requestFingerprint(),
                original.searchTerm(),
                original.window(),
                original.bindingRefs()));

        GuanceRecordingBatchReadiness view = fixture(mixed).service().inspect(7L);

        assertThat(view.executableTargetCount()).isEqualTo(19);
        assertThat(view.readyForOwnerAcceptance()).isFalse();
        assertThat(view.targets())
                .filteredOn(target -> target.targetId().equals("target-a-1"))
                .singleElement()
                .satisfies(target -> {
                    assertThat(target.scenarioKey()).isEqualTo("scenario-a-1");
                    assertThat(target.executable()).isFalse();
                    assertThat(target.targetBindingFingerprint()).isNull();
                    assertThat(target.blockers()).containsExactly(
                            "scenario-scoped target binding is not explicitly configured");
                });
    }

    private Fixture fixture(List<GuanceRecordingTargetCatalog.Target> targets) {
        GuanceRecordingTargetCatalog catalog = mock(GuanceRecordingTargetCatalog.class);
        GuanceEvidenceReadinessService readiness = mock(GuanceEvidenceReadinessService.class);
        GuanceBindingFingerprintService fingerprints =
                mock(GuanceBindingFingerprintService.class);
        when(catalog.frozenBatch()).thenReturn(new GuanceRecordingTargetCatalog.FrozenBatch(
                GuanceRecordingTargetCatalog.CONTRACT_VERSION,
                "f".repeat(64),
                targets,
                NOW.getEpochSecond()));
        when(readiness.inspect(7L, "CSDP", "service-a"))
                .thenReturn(readiness("service-a", bindings("a")));
        when(readiness.inspect(7L, "CSDP", "service-b"))
                .thenReturn(readiness("service-b", bindings("b")));
        when(fingerprints.current(7L, "CSDP", "service-a"))
                .thenReturn(Optional.of(snapshot("a", "service-a")));
        when(fingerprints.current(7L, "CSDP", "service-b"))
                .thenReturn(Optional.of(snapshot("b", "service-b")));
        return new Fixture(
                new GuanceRecordingBatchReadinessService(
                        catalog, readiness, fingerprints, CLOCK));
    }

    private List<GuanceRecordingTargetCatalog.Target> targets(
            boolean invalidateLastTarget) {
        List<GuanceRecordingTargetCatalog.Target> targets = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            targets.add(target("a", index, bindings("a")));
        }
        for (int index = 1; index <= 10; index++) {
            Map<String, String> required = invalidateLastTarget && index == 10
                    ? bindings("moved")
                    : bindings("b");
            targets.add(target("b", index, required));
        }
        return List.copyOf(targets);
    }

    private GuanceRecordingTargetCatalog.Target target(
            String serviceSuffix,
            int index,
            Map<String, String> bindings) {
        String id = "target-" + serviceSuffix + "-" + index;
        return new GuanceRecordingTargetCatalog.Target(
                id,
                "CSDP",
                "service-" + serviceSuffix,
                "csdp:error_" + serviceSuffix + "_" + index,
                "owner-sheet#" + id,
                hex(serviceSuffix, index),
                "EV-" + serviceSuffix + "-" + index,
                hex(serviceSuffix, index + 20),
                "failure-" + serviceSuffix + "-" + index,
                "-15m",
                bindings);
    }

    private GuanceEvidenceReadiness readiness(
            String service,
            Map<String, String> bindings) {
        List<GuanceEvidenceReadiness.SignalReadiness> signals = bindings.entrySet().stream()
                .map(entry -> new GuanceEvidenceReadiness.SignalReadiness(
                        entry.getKey(),
                        true,
                        GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION,
                        entry.getValue(),
                        null,
                        "ready"))
                .toList();
        return new GuanceEvidenceReadiness(
                "CSDP",
                service,
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true,
                true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true,
                signals,
                List.of());
    }

    private GuanceBindingFingerprintService.Snapshot snapshot(
            String suffix,
            String service) {
        return new GuanceBindingFingerprintService.Snapshot(
                suffix.repeat(64),
                Character.toString((char) (suffix.charAt(0) + 2)).repeat(64),
                "CSDP",
                service);
    }

    private Map<String, String> bindings(String suffix) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("log_search", "search-" + suffix);
        result.put("log_trace_bundle", "trace-" + suffix);
        result.put("contrast_sample", "contrast-" + suffix);
        return Map.copyOf(result);
    }

    private String hex(String suffix, int index) {
        char value = (char) ('0' + Math.floorMod(suffix.charAt(0) + index, 10));
        return Character.toString(value).repeat(64);
    }

    private record Fixture(GuanceRecordingBatchReadinessService service) {
    }
}
