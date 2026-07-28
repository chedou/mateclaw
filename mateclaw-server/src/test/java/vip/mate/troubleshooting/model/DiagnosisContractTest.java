package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void currentContractIsVersion16ForStructuredIncidentImpact() {
        assertEquals("1.6", Diagnosis.CURRENT_CONTRACT_VERSION);
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

        Diagnosis restored = objectMapper.treeToValue(payload, Diagnosis.class);

        assertEquals("1.4", restored.contractVersion());
        assertEquals(InvestigationMode.ERROR_CODE_PLAYBOOK, restored.investigationMode());
        assertEquals(RouteAuthority.EXPLICIT, restored.routeAuthority());
        assertEquals(ConclusionType.INSUFFICIENT_EVIDENCE, restored.conclusionType());
        assertEquals(NorthStarTimings.unrecorded(), restored.timings());
    }

    @Test
    void persistedVersion15ReadsItsLegacyImpactStringWithoutWeakeningV15Invariants()
            throws Exception {
        ObjectNode payload = objectMapper.valueToTree(diagnosis());
        payload.put("contractVersion", "1.5");
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
                "scenario:slow-api", "Slow API", base.evidence(), List.of(), List.of(),
                "API 组", false, true, List.of(), List.of());

        Diagnosis restored = objectMapper.readValue(
                objectMapper.writeValueAsString(scenario), Diagnosis.class);

        assertEquals(Diagnosis.CURRENT_CONTRACT_VERSION, restored.contractVersion());
        assertEquals(InvestigationMode.SCENARIO_PLAYBOOK, restored.investigationMode());
        assertEquals(RouteAuthority.RULE_MATCHED, restored.routeAuthority());
        assertEquals(ConclusionType.LOCATED, restored.conclusionType());
        assertEquals(timings, restored.timings());
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
                        diagnosis.evidence(),
                        diagnosis.triggeredSignals(),
                        diagnosis.recommendedActions(),
                        diagnosis.routeToTeam(),
                        diagnosis.rehearsal(),
                        diagnosis.fixtureMode(),
                        diagnosis.warnings()));
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
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "insufficient evidence",
                "unknown",
                Confidence.LOW,
                true,
                "csdp:903001",
                "SOP",
                List.of(),
                List.of(),
                List.of(),
                null,
                false,
                true,
                List.of());
    }
}
