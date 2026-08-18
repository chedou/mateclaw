package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuanceRecordingTargetCatalogTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ManualPlaybookReplayFingerprint fingerprints =
            new ManualPlaybookReplayFingerprint(mapper);

    @Test
    void bundledCatalogTruthfullyHasNoUnrecordedVerifiedTargetsYet() {
        GuanceRecordingTargetCatalog catalog = new GuanceRecordingTargetCatalog(
                mapper,
                new ClassPathResource(GuanceRecordingTargetCatalog.RESOURCE),
                selector -> true,
                selector -> false,
                CLOCK);

        GuanceRecordingTargetCatalog.View view = catalog.inspect(readiness(false));

        assertThat(view.frozenTargetCount()).isZero();
        assertThat(view.executableTargetCount()).isZero();
        assertThat(view.targets()).isEmpty();
        assertThat(view.blockers()).containsExactly(
                "only 0 server-frozen unrecorded targets exist for this scope; 20 required");
        assertThat(view.catalogFingerprint()).matches("[a-f0-9]{64}");
    }

    @Test
    void exposesTwentyTargetsOnlyWhenTheirExactRunningBindingsMatch() throws Exception {
        GuanceRecordingTargetCatalog catalog = catalog(targets(20),
                selector -> true, selector -> false);

        GuanceRecordingTargetCatalog.View view = catalog.inspect(readiness(false));

        assertThat(view.frozenTargetCount()).isEqualTo(20);
        assertThat(view.executableTargetCount()).isEqualTo(20);
        assertThat(view.targets()).hasSize(20)
                .allSatisfy(target -> {
                    assertThat(target.candidateFingerprint()).matches("[a-f0-9]{64}");
                    assertThat(target.requestFingerprint()).matches("[a-f0-9]{64}");
                    assertThat(target.bindingRefs()).isEqualTo(Map.of(
                            "log_search", "search-binding",
                            "log_trace_bundle", "trace-binding",
                            "contrast_sample", "contrast-binding"));
                });
        GuanceRecordingTargetCatalog.Target first = view.targets().getFirst();
        SopEntry firstCandidate = candidate(1);
        assertThat(first.candidateFingerprint())
                .isEqualTo(fingerprints.candidate(firstCandidate));
        assertThat(first.requestFingerprint())
                .isEqualTo(fingerprints.evidenceRequest(
                        firstCandidate.evidenceRequests().getFirst()));
        assertThat(first.selectorKey()).isEqualTo(firstCandidate.routingKey());
        assertThat(first.searchTerm()).isEqualTo("E1");
        assertThat(first.window()).isEqualTo("-15m");
        assertThat(view.blockers()).isEmpty();
        assertThat(view.asOfEpochSeconds()).isEqualTo(CLOCK.instant().getEpochSecond());
        assertThat(view.toString()).doesNotContain("D::", "L::", "DF-API-KEY");
    }

    @Test
    void freezesTheServerOwnedScenarioSelectorForPerTargetBatchIsolation()
            throws Exception {
        Map<String, Object> scenario = document(targets(1));
        candidateMap(target(scenario, 0)).put(
                "errorCode", "scenario:cti_create_session_failed");

        GuanceRecordingTargetCatalog.FrozenBatch batch = catalog(
                scenario, selector -> true, selector -> false).frozenBatch();

        assertThat(batch.targets()).singleElement().satisfies(target ->
                assertThat(target.selectorKey())
                        .isEqualTo("csdp:scenario:cti_create_session_failed"));
    }

    @Test
    void rejectsAReadyVerdictWhenTheRunningBindingMoved() throws Exception {
        GuanceRecordingTargetCatalog catalog = catalog(targets(20),
                selector -> true, selector -> false);

        GuanceRecordingTargetCatalog.View view = catalog.inspect(readiness(true));

        assertThat(view.frozenTargetCount()).isEqualTo(20);
        assertThat(view.executableTargetCount()).isZero();
        assertThat(view.targets()).isEmpty();
        assertThat(view.blockers())
                .contains("20 frozen targets do not match the running signal bindings");
    }

    @Test
    void refusesUnknownOrAlreadyRecordedSelectors() throws Exception {
        assertThatThrownBy(() -> catalog(
                targets(1), selector -> false, selector -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog is invalid")
                .hasRootCauseMessage(
                        "recording target selector is outside frozen D1 inventory");

        assertThatThrownBy(() -> catalog(
                targets(1), selector -> true, selector -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog is invalid")
                .hasRootCauseMessage(
                        "recording target selector already has recorded authority");
    }

    @Test
    void refusesUnboundedOrStructurallyExtendedServerCatalogs() throws Exception {
        Map<String, Object> badWindow = document(targets(1));
        request(target(badWindow, 0)).put("window", "-25h");
        assertThatThrownBy(() -> catalog(
                badWindow, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "INVALID_WINDOW at evidenceRequests[0].window");

        Map<String, Object> missingWindow = document(targets(1));
        request(target(missingWindow, 0)).put("window", null);
        assertThatThrownBy(() -> catalog(
                missingWindow, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target window must be a bounded relative time");

        Map<String, Object> extraField = document(targets(1));
        target(extraField, 0).put("apiKey", "must-never-enter-catalog");
        assertThatThrownBy(() -> catalog(
                extraField, selector -> true, selector -> false))
                .hasRootCauseMessage("recording target fields are invalid");

        Map<String, Object> legacySelfReportedHash = document(targets(1));
        target(legacySelfReportedHash, 0).put(
                "candidateFingerprint", "a".repeat(64));
        assertThatThrownBy(() -> catalog(
                legacySelfReportedHash, selector -> true, selector -> false))
                .hasRootCauseMessage("recording target fields are invalid");

        Map<String, Object> duplicateCandidate = document(targets(2));
        target(duplicateCandidate, 1).put(
                "candidate", target(duplicateCandidate, 0).get("candidate"));
        target(duplicateCandidate, 1).put("requiredEvidenceRequestId", "EV-1");
        assertThatThrownBy(() -> catalog(
                duplicateCandidate, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target, selector, candidate and request identities must be unique");

        assertThatThrownBy(() -> new GuanceRecordingTargetCatalog(
                new ObjectMapper(),
                new ByteArrayResource(new byte[(128 * 1024) + 1]),
                selector -> true,
                selector -> false,
                CLOCK))
                .hasRootCauseMessage(
                        "Guance recording target catalog exceeds 128 KiB");
    }

    @Test
    void refusesCandidatesWhoseSelectedRequestIsNotAnExecutableContract() throws Exception {
        Map<String, Object> missingRequest = document(targets(1));
        target(missingRequest, 0).put("requiredEvidenceRequestId", "EV-MISSING");
        assertThatThrownBy(() -> catalog(
                missingRequest, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "required evidence request must exist exactly once in the candidate");

        Map<String, Object> wrongSignal = document(targets(1));
        request(target(wrongSignal, 0)).put("signalKind", "metric");
        assertThatThrownBy(() -> catalog(
                wrongSignal, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target request must be required log_search");

        Map<String, Object> unboundRequest = document(targets(1));
        request(target(unboundRequest, 0)).put(
                "target", Map.of("operator_search", "E1"));
        assertThatThrownBy(() -> catalog(
                unboundRequest, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target request must bind only search_term");
    }

    @Test
    void refusesSemanticallyDuplicateQueriesEvenWhenTheirPublicIdentitiesDiffer()
            throws Exception {
        Map<String, Object> duplicateQuery = document(targets(2));
        request(target(duplicateQuery, 1)).put(
                "target", Map.of("search_term", "E1"));

        assertThatThrownBy(() -> catalog(
                duplicateQuery, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target query semantics must be unique");
    }

    @Test
    void refusesCandidatesWithoutACompleteDeterministicRequestToDiagnosisChain()
            throws Exception {
        Map<String, Object> promotedCandidate = document(targets(1));
        candidateMap(target(promotedCandidate, 0)).put("verified", true);
        assertThatThrownBy(() -> catalog(
                promotedCandidate, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "SOURCE_STATE_INVALID at status");

        Map<String, Object> duplicateRequest = document(targets(1));
        List<Map<String, Object>> requests = requests(target(duplicateRequest, 0));
        requests.add(new LinkedHashMap<>(requests.getFirst()));
        assertThatThrownBy(() -> catalog(
                duplicateRequest, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "DUPLICATE_EVIDENCE_REQUEST at evidenceRequests[1].requestId");

        Map<String, Object> unknownCriterionRequest = document(targets(1));
        criteria(target(unknownCriterionRequest, 0)).getFirst()
                .put("sourceRequestId", "EV-MISSING");
        assertThatThrownBy(() -> catalog(
                unknownCriterionRequest, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "UNKNOWN_EVIDENCE_REQUEST at anomalyCriteria[0].sourceRequestId");

        Map<String, Object> duplicateCriterionSignal = document(targets(1));
        List<Map<String, Object>> criteria = criteria(target(duplicateCriterionSignal, 0));
        criteria.add(new LinkedHashMap<>(criteria.getFirst()));
        assertThatThrownBy(() -> catalog(
                duplicateCriterionSignal, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "DUPLICATE_SIGNAL at anomalyCriteria[1].signal");

        Map<String, Object> unknownRuleSignal = document(targets(1));
        rules(target(unknownRuleSignal, 0)).getFirst()
                .put("requiredSignals", List.of("unknown_signal"));
        assertThatThrownBy(() -> catalog(
                unknownRuleSignal, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "UNKNOWN_REQUIRED_SIGNAL at diagnosisRules[0].requiredSignals[0]");

        Map<String, Object> disconnectedSelectedRequest = document(targets(1));
        Map<String, Object> secondRequest =
                new LinkedHashMap<>(requests(target(disconnectedSelectedRequest, 0)).getFirst());
        secondRequest.put("requestId", "EV-DISCONNECTED");
        secondRequest.put("target", Map.of("search_term", "E-DISCONNECTED"));
        requests(target(disconnectedSelectedRequest, 0)).add(secondRequest);
        target(disconnectedSelectedRequest, 0)
                .put("requiredEvidenceRequestId", "EV-DISCONNECTED");
        assertThatThrownBy(() -> catalog(
                disconnectedSelectedRequest, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target request must feed a deterministic diagnosis rule");
    }

    @Test
    void reusesTheSharedManualContractForNestedSecretsDqlAndDangerousActions()
            throws Exception {
        Map<String, Object> secretInCause = document(targets(1));
        candidateMap(target(secretInCause, 0))
                .put("cause", "api-key: secret-value-must-never-enter");
        assertThatThrownBy(() -> catalog(
                secretInCause, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "SECRET_NOT_REDACTED at cause");

        Map<String, Object> dqlInAuxiliaryRequest = document(targets(1));
        Map<String, Object> auxiliary =
                new LinkedHashMap<>(requests(target(dqlInAuxiliaryRequest, 0)).getFirst());
        auxiliary.put("requestId", "EV-AUX");
        auxiliary.put("signalKind", "log_trace_bundle");
        auxiliary.put("target", Map.of("query", "L::raw:(*)"));
        requests(target(dqlInAuxiliaryRequest, 0)).add(auxiliary);
        assertThatThrownBy(() -> catalog(
                dqlInAuxiliaryRequest, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "DQL_OR_RAW_LOG_FORBIDDEN at evidenceRequests[1].target.query");

        Map<String, Object> dangerousAction = document(targets(1));
        actions(target(dangerousAction, 0)).add(new LinkedHashMap<>(Map.of(
                "actionId", "ACTION-1",
                "actionType", "AUTO_READONLY",
                "title", "restart production",
                "description", "unsafe automatic action",
                "requiresApproval", false,
                "approvalStatus", "NOT_REQUIRED",
                "executionStatus", "PENDING")));
        assertThatThrownBy(() -> catalog(
                dangerousAction, selector -> true, selector -> false))
                .hasRootCauseMessage(
                        "recording target candidate violates shared manual Playbook contract: "
                                + "PRODUCTION_WRITE_FORBIDDEN at actions[0].title");
    }

    @Test
    void refusesDuplicateKeysAndTrailingRootValuesBeforeInterpretingTheCatalog() {
        String duplicateKey = """
                {"contractVersion":"evil",
                 "contractVersion":"t7-guance-recording-target-catalog.v1",
                 "targets":[]}
                """;
        assertThatThrownBy(() -> rawCatalog(duplicateKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog is invalid")
                .hasStackTraceContaining("Duplicate field 'contractVersion'");

        String trailingRoot = """
                {"contractVersion":"t7-guance-recording-target-catalog.v1","targets":[]}
                {"ignored":true}
                """;
        assertThatThrownBy(() -> rawCatalog(trailingRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog is invalid")
                .hasStackTraceContaining("Trailing token");
    }

    private GuanceRecordingTargetCatalog catalog(
            List<Map<String, Object>> targets,
            java.util.function.Predicate<String> selectorKnown,
            java.util.function.Predicate<String> alreadyRecorded) throws Exception {
        return catalog(document(targets), selectorKnown, alreadyRecorded);
    }

    private GuanceRecordingTargetCatalog catalog(
            Map<String, Object> document,
            java.util.function.Predicate<String> selectorKnown,
            java.util.function.Predicate<String> alreadyRecorded) throws Exception {
        return new GuanceRecordingTargetCatalog(
                mapper,
                new ByteArrayResource(mapper.writeValueAsBytes(document)),
                selectorKnown,
                alreadyRecorded,
                CLOCK);
    }

    private GuanceRecordingTargetCatalog rawCatalog(String json) {
        return new GuanceRecordingTargetCatalog(
                mapper,
                new ByteArrayResource(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                selector -> true,
                selector -> false,
                CLOCK);
    }

    private Map<String, Object> document(List<Map<String, Object>> targets) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("contractVersion", GuanceRecordingTargetCatalog.CONTRACT_VERSION);
        document.put("targets", targets);
        return document;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> target(Map<String, Object> document, int index) {
        return ((List<Map<String, Object>>) document.get("targets")).get(index);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(Map<String, Object> target) {
        return requests(target).getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> candidateMap(Map<String, Object> target) {
        return (Map<String, Object>) target.get("candidate");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requests(Map<String, Object> target) {
        return (List<Map<String, Object>>) candidateMap(target).get("evidenceRequests");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> criteria(Map<String, Object> target) {
        return (List<Map<String, Object>>) candidateMap(target).get("anomalyCriteria");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rules(Map<String, Object> target) {
        return (List<Map<String, Object>>) candidateMap(target).get("diagnosisRules");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> actions(Map<String, Object> target) {
        return (List<Map<String, Object>>) candidateMap(target).get("actions");
    }

    private List<Map<String, Object>> targets(int count) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("targetId", "target-" + index);
            target.put("candidateReference", "candidate-source-" + index);
            target.put("requiredEvidenceRequestId", "EV-" + index);
            target.put("bindingRefs", Map.of(
                    "log_search", "search-binding",
                    "log_trace_bundle", "trace-binding",
                    "contrast_sample", "contrast-binding"));
            target.put("candidate", mapper.convertValue(candidate(index), Map.class));
            targets.add(target);
        }
        return targets;
    }

    private SopEntry candidate(int index) {
        String requestId = "EV-" + index;
        String signal = "failure_present_" + index;
        EvidenceRequest request = new EvidenceRequest(
                requestId,
                "log_search",
                "locate failed samples",
                Map.of("search_term", "E" + index),
                "-15m",
                true);
        return new SopEntry(
                "candidate-" + index,
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "E" + index,
                "session-svc",
                "Candidate " + index,
                "Cause " + index,
                "message",
                "message-team",
                "candidate",
                false,
                List.of(request),
                List.of(new AnomalyCriterion(
                        signal,
                        requestId,
                        "failure exists",
                        new Criterion.NumericGte("match_count", 1))),
                List.of(new DiagnosisRule(
                        "RULE-" + index,
                        List.of(signal),
                        "candidate root cause",
                        "continue read-only diagnosis",
                        Confidence.MEDIUM,
                        false)),
                List.of());
    }

    private GuanceEvidenceReadiness readiness(boolean movedBinding) {
        return new GuanceEvidenceReadiness(
                "CSDP",
                "session-svc",
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true,
                true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true,
                List.of(
                        signal("log_search", movedBinding ? "moved-search" : "search-binding"),
                        signal("log_trace_bundle", "trace-binding"),
                        signal("contrast_sample", "contrast-binding")),
                List.of());
    }

    private GuanceEvidenceReadiness.SignalReadiness signal(
            String signalKind,
            String bindingRef) {
        return new GuanceEvidenceReadiness.SignalReadiness(
                signalKind,
                true,
                GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION,
                bindingRef,
                null,
                "ready");
    }
}
