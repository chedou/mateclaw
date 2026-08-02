package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void currentContractIsVersion18ForFrozenPlaybookAuthority() {
        assertEquals("1.8", Diagnosis.CURRENT_CONTRACT_VERSION);
    }

    @Test
    void persistedPayloadRejectsUnknownContractVersion() throws Exception {
        String json = objectMapper.writeValueAsString(diagnosis())
                .replace(
                        "\"contractVersion\":\"" + Diagnosis.CURRENT_CONTRACT_VERSION + "\"",
                        "\"contractVersion\":\"9.9\"");

        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.readValue(json, Diagnosis.class));
    }

    @Test
    void persistedPayloadRejectsMissingContractVersion() throws Exception {
        String json = objectMapper.writeValueAsString(diagnosis())
                .replace(
                        "\"contractVersion\":\"" + Diagnosis.CURRENT_CONTRACT_VERSION + "\",",
                        "");

        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.readValue(json, Diagnosis.class));
    }

    @Test
    void persistedVersion13PayloadDefaultsMissingEvidenceCitationsToEmpty() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.put("contractVersion", "1.3");
        payload.remove("evidenceCitations");
        payload.remove("sourcePlaybookVersionRef");

        Diagnosis restored = objectMapper.treeToValue(payload, Diagnosis.class);

        assertEquals("1.3", restored.contractVersion());
        assertTrue(restored.evidenceCitations().isEmpty());
    }

    @Test
    void persistedVersion14PayloadDefaultsMissingV15ExperienceFieldsWithoutInventingTimings()
            throws Exception {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.put("contractVersion", "1.4");
        payload.remove("investigationMode");
        payload.remove("routeAuthority");
        payload.remove("conclusionType");
        payload.remove("timings");
        payload.remove("sourcePlaybookVersionRef");

        Diagnosis restored = objectMapper.treeToValue(payload, Diagnosis.class);

        assertEquals("1.4", restored.contractVersion());
        assertEquals(InvestigationMode.ERROR_CODE_PLAYBOOK, restored.investigationMode());
        assertEquals(RouteAuthority.EXPLICIT, restored.routeAuthority());
        assertEquals(ConclusionType.INSUFFICIENT_EVIDENCE, restored.conclusionType());
        assertEquals(NorthStarTimings.unrecorded(), restored.timings());
        assertEquals(RouteSemanticsProvenance.LEGACY_DERIVED, restored.routeSemanticsProvenance());
    }

    @Test
    void persistedVersion15ReadsItsLegacyImpactStringWithoutWeakeningV15Invariants()
            throws Exception {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.put("contractVersion", "1.5");
        payload.remove("sourcePlaybookVersionRef");
        ((ObjectNode) payload.path("incident")).put("impact", "订单创建功能受影响");

        Diagnosis restored = objectMapper.treeToValue(payload, Diagnosis.class);

        assertEquals("1.5", restored.contractVersion());
        assertEquals("订单创建功能受影响", restored.incident().impact().functionScope());
        assertEquals(BlastRadius.UNKNOWN, restored.incident().impact().blastRadius());

        payload.remove("investigationMode");
        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.treeToValue(payload, Diagnosis.class));
    }

    @Test
    void currentContractPersistsScenarioAuthorityAndAllNorthStarIntervals() throws Exception {
        Instant reportedAt = Instant.parse("2026-07-25T01:00:00Z");
        Instant readyAt = Instant.parse("2026-07-25T01:00:30Z");
        Instant conclusionAt = Instant.parse("2026-07-25T01:02:00Z");
        NorthStarTimings timings = NorthStarTimings.concluded(
                reportedAt, readyAt, conclusionAt);
        Diagnosis base = diagnosis();
        Diagnosis scenario = Diagnosis.initial(
                base.diagnosisId(), base.caseId(), base.runId(), base.incident(),
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.RULE_MATCHED,
                ConclusionType.LOCATED,
                timings,
                DiagnosisStatus.READY_FOR_HUMAN,
                "scenario located", "slow dependency", Confidence.MEDIUM, false,
                "scenario:slow-api", "Slow API", "API 组",
                new PlaybookVersionRef("playbook-scenario", 4),
                base.evidence(), List.of(), List.of(),
                "API 组", false, true, List.of(), List.of());

        Diagnosis restored = objectMapper.readValue(
                objectMapper.writeValueAsString(scenario), Diagnosis.class);

        assertEquals(Diagnosis.CURRENT_CONTRACT_VERSION, restored.contractVersion());
        assertEquals(InvestigationMode.SCENARIO_PLAYBOOK, restored.investigationMode());
        assertEquals(RouteAuthority.RULE_MATCHED, restored.routeAuthority());
        assertEquals(ConclusionType.LOCATED, restored.conclusionType());
        assertEquals(timings, restored.timings());
        assertEquals(RouteSemanticsProvenance.PERSISTED, restored.routeSemanticsProvenance());
        assertEquals(
                new PlaybookVersionRef("playbook-scenario", 4),
                restored.sourcePlaybookVersionRef());
    }

    @Test
    void currentDeterministicContractRejectsMissingExactPlaybookVersion() {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.remove("sourcePlaybookVersionRef");

        assertThrows(
                JsonProcessingException.class,
                () -> objectMapper.treeToValue(payload, Diagnosis.class));
    }

    @Test
    void version17WithoutExactPlaybookVersionRemainsReadableWithoutGuessing() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.put("contractVersion", "1.7");
        payload.remove("sourcePlaybookVersionRef");

        Diagnosis restored = objectMapper.treeToValue(payload, Diagnosis.class);

        assertEquals("1.7", restored.contractVersion());
        assertNull(restored.sourcePlaybookVersionRef());
    }

    @Test
    void version16PayloadWithoutFrozenPlaybookOwnerRemainsReadable() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.put("contractVersion", "1.6");
        payload.remove("sourcePlaybookOwner");
        payload.remove("sourcePlaybookVersionRef");

        Diagnosis restored = objectMapper.treeToValue(payload, Diagnosis.class);

        assertEquals("1.6", restored.contractVersion());
        assertNull(restored.sourcePlaybookOwner());
    }

    @Test
    void initialFactoryRejectsLifecycleJump() {
        Diagnosis diagnosis = diagnosis();

        assertThrows(
                IllegalArgumentException.class,
                () -> Diagnosis.initial(
                        diagnosis.diagnosisId(),
                        diagnosis.caseId(),
                        diagnosis.runId(),
                        diagnosis.incident(),
                        diagnosis.routeMode(),
                        DiagnosisStatus.CLOSED,
                        diagnosis.summary(),
                        diagnosis.rootCause(),
                        diagnosis.confidence(),
                        false,
                        diagnosis.sopKey(),
                        diagnosis.sopTitle(),
                        diagnosis.sourcePlaybookVersionRef(),
                        diagnosis.evidence(),
                        diagnosis.triggeredSignals(),
                        diagnosis.recommendedActions(),
                        diagnosis.routeToTeam(),
                        diagnosis.rehearsal(),
                        diagnosis.fixtureMode(),
                        diagnosis.warnings()));
    }

    @Test
    void currentFactoryCannotCreateALegacyDeterministicDiagnosisWithoutExactAuthority() {
        Diagnosis base = diagnosis();

        assertThrows(
                IllegalArgumentException.class,
                () -> Diagnosis.initial(
                        base.diagnosisId(), base.caseId(), base.runId(), base.incident(),
                        RouteMode.DETERMINISTIC, DiagnosisStatus.READY_FOR_HUMAN,
                        base.summary(), base.rootCause(), base.confidence(), false,
                        base.sopKey(), base.sopTitle(), base.evidence(),
                        base.triggeredSignals(), base.recommendedActions(), null,
                        false, true, List.of()));
    }

    @Test
    void currentScenarioPlaybookRulesIgnoreConflictingLegacyRouteMode() {
        Diagnosis conflicting = Diagnosis.initial(
                "diag-scenario-conflict",
                "case-scenario-conflict",
                "run-scenario-conflict",
                diagnosis().incident(),
                RouteMode.LLM_FALLBACK,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.RULE_MATCHED,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(
                        Instant.parse("2026-07-25T01:00:00Z"),
                        Instant.parse("2026-07-25T01:00:30Z"),
                        Instant.parse("2026-07-25T01:02:00Z")),
                DiagnosisStatus.READY_FOR_HUMAN,
                "scenario located",
                "rule matched scenario",
                Confidence.MEDIUM,
                false,
                "scenario:slow-api",
                "Slow API",
                "API 组",
                new PlaybookVersionRef("playbook-scenario", 4),
                diagnosis().evidence(),
                List.of(),
                List.of(),
                "API 组",
                false,
                true,
                List.of(),
                List.of());

        assertEquals(InvestigationMode.SCENARIO_PLAYBOOK, conflicting.investigationMode());
        assertEquals(RouteAuthority.RULE_MATCHED, conflicting.routeAuthority());
        assertEquals(ConclusionType.LOCATED, conflicting.conclusionType());
        assertEquals(Confidence.MEDIUM, conflicting.confidence());
        assertEquals(
                new PlaybookVersionRef("playbook-scenario", 4),
                conflicting.sourcePlaybookVersionRef());
    }

    @Test
    void currentOpenDiscoveryRulesIgnoreConflictingDeterministicRouteMode() {
        Diagnosis conflicting = openDiscoveryDiagnosis(RouteMode.DETERMINISTIC, Confidence.MEDIUM);

        assertEquals(InvestigationMode.OPEN_DISCOVERY, conflicting.investigationMode());
        assertEquals(RouteAuthority.MODEL_PROPOSED, conflicting.routeAuthority());
        assertEquals(ConclusionType.HYPOTHESIS, conflicting.conclusionType());
        assertEquals(Confidence.MEDIUM, conflicting.confidence());
        assertEquals(List.of(), conflicting.recommendedActions());
        assertEquals(List.of(), conflicting.pendingWrites());
        assertEquals(null, conflicting.sourcePlaybookVersionRef());
    }

    @Test
    void currentOpenDiscoveryStillRejectsExactPlaybookVersionAndHighModelConfidence() {
        Diagnosis base = diagnosis();

        assertThrows(
                IllegalArgumentException.class,
                () -> Diagnosis.initial(
                        "diag-open-exact-version",
                        "case-open-exact-version",
                        "run-open-exact-version",
                        base.incident(),
                        RouteMode.LLM_FALLBACK,
                        InvestigationMode.OPEN_DISCOVERY,
                        RouteAuthority.MODEL_PROPOSED,
                        ConclusionType.HYPOTHESIS,
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-25T01:00:00Z"),
                                Instant.parse("2026-07-25T01:00:30Z"),
                                Instant.parse("2026-07-25T01:02:00Z")),
                        DiagnosisStatus.READY_FOR_HUMAN,
                        "open discovery hypothesis",
                        "needs more evidence",
                        Confidence.MEDIUM,
                        false,
                        "scenario:slow-api",
                        "Slow API",
                        null,
                        new PlaybookVersionRef("playbook-scenario", 4),
                        base.evidence(),
                        List.of("hypothesis"),
                        List.of(),
                        "API 组",
                        false,
                        true,
                        List.of(),
                        List.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> Diagnosis.initial(
                        "diag-open-high-confidence",
                        "case-open-high-confidence",
                        "run-open-high-confidence",
                        base.incident(),
                        RouteMode.LLM_FALLBACK,
                        InvestigationMode.OPEN_DISCOVERY,
                        RouteAuthority.MODEL_PROPOSED,
                        ConclusionType.HYPOTHESIS,
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-25T01:00:00Z"),
                                Instant.parse("2026-07-25T01:00:30Z"),
                                Instant.parse("2026-07-25T01:02:00Z")),
                        DiagnosisStatus.READY_FOR_HUMAN,
                        "open discovery hypothesis",
                        "needs more evidence",
                        Confidence.HIGH,
                        false,
                        null,
                        null,
                        null,
                        null,
                        base.evidence(),
                        List.of("hypothesis"),
                        List.of(),
                        "API 组",
                        false,
                        true,
                        List.of(),
                        List.of()));
    }

    @Test
    void currentScenarioPlaybookStillRejectsMissingExactVersion() {
        Diagnosis base = diagnosis();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Diagnosis.initial(
                        "diag-scenario-missing-version",
                        "case-scenario-missing-version",
                        "run-scenario-missing-version",
                        base.incident(),
                        RouteMode.LLM_FALLBACK,
                        InvestigationMode.SCENARIO_PLAYBOOK,
                        RouteAuthority.RULE_MATCHED,
                        ConclusionType.LOCATED,
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-25T01:00:00Z"),
                                Instant.parse("2026-07-25T01:00:30Z"),
                                Instant.parse("2026-07-25T01:02:00Z")),
                        DiagnosisStatus.READY_FOR_HUMAN,
                        "scenario located",
                        "rule matched scenario",
                        Confidence.MEDIUM,
                        false,
                        "scenario:slow-api",
                        "Slow API",
                        "API 组",
                        null,
                        base.evidence(),
                        List.of(),
                        List.of(),
                        "API 组",
                        false,
                        true,
                        List.of(),
                        List.of()));

        assertTrue(error.getMessage().contains("exact Playbook version"));
    }

    @Test
    void currentOpenDiscoveryStillRejectsRecommendedActionsAndPendingWrites() {
        Diagnosis base = diagnosis();
        RecommendedAction manualWrite =
                RecommendedAction.manualWrite("manual-1", "restart", "external only");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new Diagnosis(
                        "diag-open-actions",
                        Diagnosis.CURRENT_CONTRACT_VERSION,
                        "case-open-actions",
                        "run-open-actions",
                        base.incident(),
                        RouteMode.DETERMINISTIC,
                        InvestigationMode.OPEN_DISCOVERY,
                        RouteAuthority.MODEL_PROPOSED,
                        ConclusionType.HYPOTHESIS,
                        DiagnosisStatus.READY_FOR_HUMAN,
                        "open discovery hypothesis",
                        "needs more evidence",
                        Confidence.MEDIUM,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of(new EvidenceResult(
                                "EV-1",
                                "L",
                                "L::open-discovery",
                                EvidenceStatus.ANOMALY,
                                "open discovery evidence",
                                java.util.Map.of("count", 1),
                                "recorded-replay",
                                Instant.parse("2026-07-25T01:01:00Z"))),
                        List.of("EV-1"),
                        List.of("hypothesis"),
                        List.of(manualWrite),
                        List.of(manualWrite),
                        "API 组",
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-25T01:00:00Z"),
                                Instant.parse("2026-07-25T01:00:30Z"),
                                Instant.parse("2026-07-25T01:02:00Z")),
                        false,
                        true,
                        false,
                        List.of()));

        assertTrue(error.getMessage().contains("OPEN_DISCOVERY"));
        assertTrue(error.getMessage().contains("actions"));
    }

    private Diagnosis openDiscoveryDiagnosis(RouteMode routeMode, Confidence confidence) {
        Diagnosis base = diagnosis();
        return new Diagnosis(
                "diag-open-conflict",
                Diagnosis.CURRENT_CONTRACT_VERSION,
                "case-open-conflict",
                "run-open-conflict",
                base.incident(),
                routeMode,
                InvestigationMode.OPEN_DISCOVERY,
                RouteAuthority.MODEL_PROPOSED,
                ConclusionType.HYPOTHESIS,
                DiagnosisStatus.READY_FOR_HUMAN,
                "open discovery hypothesis",
                "needs more evidence",
                confidence,
                false,
                null,
                null,
                null,
                null,
                List.of(new EvidenceResult(
                        "EV-1",
                        "L",
                        "L::open-discovery",
                        EvidenceStatus.ANOMALY,
                        "open discovery evidence",
                        java.util.Map.of("count", 1),
                        "recorded-replay",
                        Instant.parse("2026-07-25T01:01:00Z"))),
                List.of("EV-1"),
                List.of("hypothesis"),
                List.of(),
                List.of(),
                "API 组",
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                NorthStarTimings.concluded(
                        Instant.parse("2026-07-25T01:00:00Z"),
                        Instant.parse("2026-07-25T01:00:30Z"),
                        Instant.parse("2026-07-25T01:02:00Z")),
                false,
                true,
                false,
                List.of());
    }

    @Test
    void aggregateCannotCloseRecoveredIncidentBeforeApprovalAndVerifiedOutcome() {
        Diagnosis base = diagnosis();
        Diagnosis ready = Diagnosis.initial(
                base.diagnosisId(),
                base.caseId(),
                base.runId(),
                base.incident(),
                base.routeMode(),
                DiagnosisStatus.READY_FOR_HUMAN,
                "ready",
                "MongoDB unavailable",
                Confidence.HIGH,
                false,
                base.sopKey(),
                base.sopTitle(),
                base.sourcePlaybookVersionRef(),
                base.evidence(),
                base.triggeredSignals(),
                List.of(RecommendedAction.manualWrite(
                        "restart-mongodb", "restart MongoDB", "external only")),
                null,
                false,
                true,
                List.of());
        TimelineEvent confirmation = new TimelineEvent(
                Instant.parse("2026-07-25T01:01:00Z"),
                "confirmed",
                "on-call",
                "done");
        Diagnosis confirmed = ready.confirmed(List.of(confirmation));
        ClosureRecord recovered = new ClosureRecord(
                ClosureOutcome.RECOVERED,
                "recovered",
                true,
                null,
                null,
                "on-call",
                Instant.parse("2026-07-25T01:02:00Z"));
        TimelineEvent closing = new TimelineEvent(
                Instant.parse("2026-07-25T01:02:00Z"),
                "closed",
                "on-call",
                "done");

        assertThrows(
                IllegalArgumentException.class,
                () -> confirmed.closed(recovered, List.of(), List.of(confirmation, closing)));
    }

    private Diagnosis diagnosis() {
        IncidentContext incident = new IncidentContext(
                "inc-contract",
                "CSDP",
                "csdp-wechat",
                "903001",
                "database error",
                "P1",
                "pending",
                null,
                Instant.parse("2026-07-25T01:00:00Z"),
                null,
                "test",
                IncidentCompleteness.STRUCTURED,
                null);
        return Diagnosis.initial(
                "diag-contract",
                "case-contract",
                "run-contract",
                incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.ERROR_CODE_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.INSUFFICIENT_EVIDENCE,
                NorthStarTimings.unrecorded(),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "insufficient evidence",
                "unknown",
                Confidence.LOW,
                true,
                "csdp:903001",
                "SOP",
                "DBA 组",
                new PlaybookVersionRef("playbook-contract", 3),
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                true,
                List.of(),
                List.of());
    }
}
