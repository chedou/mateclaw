package vip.mate.troubleshooting.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;
import vip.mate.troubleshooting.service.DiagnosisSummary;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TroubleshootingPersistenceServiceTest {

    @Mock private TroubleshootingDiagnosisMapper diagnosisMapper;
    @Mock private TroubleshootingKnowledgeOutboxMapper outboxMapper;
    @Mock private TroubleshootingPilotPlanService pilotPlans;

    private ObjectMapper objectMapper;
    private TroubleshootingPersistenceService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                TroubleshootingDiagnosisEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                TroubleshootingKnowledgeOutboxEntity.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TroubleshootingPersistenceService(
                diagnosisMapper, outboxMapper, objectMapper, pilotPlans);
    }

    @Test
    void pilotPlanVersionIsInsertOnlyAtTheEntityMappingBoundary() {
        var field = TableInfoHelper.getTableInfo(TroubleshootingDiagnosisEntity.class)
                .getFieldList()
                .stream()
                .filter(candidate -> "pilotPlanVersion".equals(candidate.getProperty()))
                .findFirst()
                .orElseThrow();

        assertEquals(FieldStrategy.NEVER, field.getUpdateStrategy());
    }

    @Test
    void createStoresAggregateAndCompositeDeduplicationKey() {
        when(diagnosisMapper.selectOne(any())).thenReturn(null);
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);

        StoredDiagnosis stored = service.createOrGet(
                7L,
                diagnosis(false),
                Instant.parse("2026-07-25T01:04:59Z"));

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entity =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper).insert(entity.capture());
        assertEquals(7L, entity.getValue().getWorkspaceId());
        assertEquals("diag-1", entity.getValue().getDiagnosisId());
        assertEquals(64, entity.getValue().getDedupKey().length());
        assertTrue(entity.getValue().getAggregateJson().contains("diag-1"));
        assertEquals(InvestigationMode.ERROR_CODE_PLAYBOOK.name(),
                entity.getValue().getInvestigationMode());
        assertEquals(RouteAuthority.EXPLICIT.name(),
                entity.getValue().getRouteAuthority());
        assertEquals(0, stored.version());
        assertTrue(stored.created());
    }

    @Test
    void freezesTheCurrentPilotVersionOnlyWhenTheDiagnosisIsFirstCreated() {
        when(diagnosisMapper.selectOne(any())).thenReturn(null);
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);
        when(pilotPlans.enrollmentVersion(
                7L, "CSDP", "csdp-wechat", false)).thenReturn(7);

        service.createOrGet(
                7L,
                diagnosis(false),
                Instant.parse("2026-07-25T01:04:59Z"));

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entity =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper).insert(entity.capture());
        assertEquals(7, entity.getValue().getPilotPlanVersion());
    }

    @Test
    void legacyCreateKeepsRouteSemanticsIndexesNullEvenThoughDomainDerivesThem() throws Exception {
        when(diagnosisMapper.selectOne(any())).thenReturn(null);
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);

        Diagnosis legacy = legacyDiagnosis();

        service.createOrGet(
                7L,
                legacy,
                Instant.parse("2026-07-25T01:04:59Z"));

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entity =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper).insert(entity.capture());
        assertEquals(RouteSemanticsProvenance.LEGACY_DERIVED, legacy.routeSemanticsProvenance());
        assertEquals(InvestigationMode.ERROR_CODE_PLAYBOOK, legacy.investigationMode());
        assertEquals(RouteAuthority.EXPLICIT, legacy.routeAuthority());
        assertEquals(null, entity.getValue().getInvestigationMode());
        assertEquals(null, entity.getValue().getRouteAuthority());
    }

    @Test
    void symptomOnlyProductionDiagnosisAlsoStoresAFiveMinuteDeduplicationKey() {
        when(diagnosisMapper.selectOne(any())).thenReturn(null);
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);

        service.createOrGet(
                7L,
                symptomDiagnosis(false),
                Instant.parse("2026-07-25T01:04:59Z"));

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entity =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper).insert(entity.capture());
        assertNotNull(entity.getValue().getDedupKey());
        assertEquals(64, entity.getValue().getDedupKey().length());
    }

    @Test
    void scenarioIntakeDoesNotReuseTheGenericSymptomDeduplicationNamespace() {
        when(diagnosisMapper.selectOne(any())).thenReturn(null);
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);
        Instant receivedAt = Instant.parse("2026-07-25T01:04:59Z");

        service.createOrGet(7L, symptomDiagnosis(false), receivedAt);
        service.createOrGetForScenario(
                7L,
                scenarioDiagnosis(false),
                "deployment_topology_probe",
                receivedAt);

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entities =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper, org.mockito.Mockito.times(2)).insert(entities.capture());
        assertNotEquals(
                entities.getAllValues().get(0).getDedupKey(),
                entities.getAllValues().get(1).getDedupKey());
    }

    @Test
    void scenarioNamespaceRejectsDiagnosesFromAnotherInvestigationModeOrSelector() {
        Instant receivedAt = Instant.parse("2026-07-25T01:04:59Z");

        IllegalArgumentException wrongMode = assertThrows(
                IllegalArgumentException.class,
                () -> service.createOrGetForScenario(
                        7L,
                        symptomDiagnosis(false),
                        "deployment_topology_probe",
                        receivedAt));
        IllegalArgumentException wrongSelector = assertThrows(
                IllegalArgumentException.class,
                () -> service.createOrGetForScenario(
                        7L,
                        scenarioDiagnosis(false),
                        "slow_api",
                        receivedAt));

        assertTrue(wrongMode.getMessage().contains("SCENARIO_PLAYBOOK"));
        assertTrue(wrongSelector.getMessage().contains("diagnosis selector"));
        verify(diagnosisMapper, never()).insert(any(TroubleshootingDiagnosisEntity.class));
    }

    @Test
    void rehearsalSkipsDeduplicationLookupAndStoresNullKey() {
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);

        service.createOrGet(7L, diagnosis(true), Instant.parse("2026-07-25T01:04:59Z"));

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entity =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper, never()).selectOne(any());
        verify(diagnosisMapper).insert(entity.capture());
        assertEquals(null, entity.getValue().getDedupKey());
    }

    @Test
    void intakeOwnershipIsDurableAndDoesNotShareTheFiveMinuteIncidentBucket() {
        when(diagnosisMapper.selectOne(any())).thenReturn(null);
        when(diagnosisMapper.insert(any(TroubleshootingDiagnosisEntity.class))).thenReturn(1);

        StoredDiagnosis stored = service.createOrGetForIntake(
                7L,
                diagnosis(false),
                "intake-7");

        ArgumentCaptor<TroubleshootingDiagnosisEntity> entity =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisEntity.class);
        verify(diagnosisMapper).insert(entity.capture());
        assertEquals("intake-7", entity.getValue().getSourceIntakeSessionId());
        assertEquals(null, entity.getValue().getDedupKey());
        assertTrue(stored.created());
    }

    @Test
    void retryingTheSameIntakeReturnsItsExistingDiagnosis() throws Exception {
        TroubleshootingDiagnosisEntity existing = persisted(diagnosis(false), 4);
        existing.setSourceIntakeSessionId("intake-7");
        when(diagnosisMapper.selectOne(any())).thenReturn(existing);

        StoredDiagnosis stored = service.createOrGetForIntake(
                7L,
                diagnosis(false),
                "intake-7");

        assertEquals("diag-1", stored.diagnosis().diagnosisId());
        assertEquals(4, stored.version());
        assertFalse(stored.created());
        verify(diagnosisMapper, never()).insert(any(TroubleshootingDiagnosisEntity.class));
        verifyNoInteractions(pilotPlans);
    }

    @Test
    void findsPersistedDiagnosisByItsSourceIntakeWithoutRerunningInvestigation() throws Exception {
        TroubleshootingDiagnosisEntity existing = persisted(diagnosis(false), 4);
        existing.setSourceIntakeSessionId("intake-7");
        when(diagnosisMapper.selectOne(any())).thenReturn(existing);

        StoredDiagnosis stored = service.findByIntakeSessionId(7L, "intake-7").orElseThrow();

        assertEquals("diag-1", stored.diagnosis().diagnosisId());
        assertEquals(4, stored.version());
        assertFalse(stored.created());
        verify(diagnosisMapper, never()).insert(any(TroubleshootingDiagnosisEntity.class));
    }

    @Test
    void duplicateReturnsPreviouslyPersistedAggregate() throws Exception {
        Diagnosis existing = diagnosis(
                false,
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "existing",
                "已有诊断",
                Confidence.LOW,
                true);
        TroubleshootingDiagnosisEntity entity = persisted(existing, 3);
        when(diagnosisMapper.selectOne(any())).thenReturn(entity);

        StoredDiagnosis stored = service.createOrGet(
                7L,
                diagnosis(false),
                Instant.parse("2026-07-25T01:04:59Z"));

        assertEquals("已有诊断", stored.diagnosis().rootCause());
        assertEquals(3, stored.version());
        assertFalse(stored.created());
        verify(diagnosisMapper, never()).insert(any(TroubleshootingDiagnosisEntity.class));
        verifyNoInteractions(pilotPlans);
    }

    @Test
    void updateAndEnqueueWritesAggregateAndOutboxInOneServiceTransaction() {
        when(diagnosisMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(outboxMapper.selectOne(any())).thenReturn(null);
        when(outboxMapper.insert(any(TroubleshootingKnowledgeOutboxEntity.class))).thenReturn(1);
        KnowledgeCandidate candidate = candidate();
        Diagnosis diagnosis = diagnosisWithCandidate(candidate);

        StoredDiagnosis updated = service.updateAndEnqueue(
                7L,
                diagnosis,
                2,
                candidate);

        ArgumentCaptor<TroubleshootingKnowledgeOutboxEntity> outbox =
                ArgumentCaptor.forClass(TroubleshootingKnowledgeOutboxEntity.class);
        verify(outboxMapper).insert(outbox.capture());
        assertEquals("publication-candidate-1", outbox.getValue().getPublicationId());
        assertEquals(
                KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                outbox.getValue().getContractVersion());
        assertEquals(7L, outbox.getValue().getWorkspaceId());
        assertEquals(KnowledgePublicationStatus.PENDING, outbox.getValue().getStatus());
        assertTrue(outbox.getValue().getPayloadJson().contains("candidate-1"));
        assertEquals(3, updated.version());
    }

    @Test
    void updateWritesPersistedRouteSemanticsIntoIndexedColumns() {
        when(diagnosisMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.update(7L, diagnosis(false), 2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TroubleshootingDiagnosisEntity>> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(diagnosisMapper).update(eq(null), update.capture());
        String sqlSet = update.getValue().getSqlSet();
        Map<String, Object> params = update.getValue().getParamNameValuePairs();
        assertTrue(sqlSet.contains("investigation_mode"), sqlSet);
        assertTrue(sqlSet.contains("route_authority"), sqlSet);
        assertFalse(sqlSet.contains("pilot_plan_version"), sqlSet);
        assertTrue(params.containsValue(InvestigationMode.ERROR_CODE_PLAYBOOK.name()), params.toString());
        assertTrue(params.containsValue(RouteAuthority.EXPLICIT.name()), params.toString());
    }

    @Test
    void typedListFiltersByIndexedInvestigationModeWithoutParsingAggregateJson() {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setDiagnosisId("diag-scenario");
        entity.setCaseId("case-scenario");
        entity.setSystem("CSDP");
        entity.setErrorCode("903001");
        entity.setService("csdp-wechat");
        entity.setStatus(DiagnosisStatus.NEEDS_INVESTIGATION.name());
        entity.setRehearsal(false);
        entity.setVersion(2);
        entity.setContractVersion(Diagnosis.CURRENT_CONTRACT_VERSION);
        entity.setInvestigationMode(InvestigationMode.SCENARIO_PLAYBOOK.name());
        entity.setRouteAuthority(RouteAuthority.RULE_MATCHED.name());
        entity.setPilotPlanVersion(7);
        entity.setAggregateJson("{not-json");
        when(diagnosisMapper.selectList(any())).thenReturn(List.of(entity));

        List<DiagnosisSummary> rows = service.list(
                7L, null, null, InvestigationMode.SCENARIO_PLAYBOOK, 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<TroubleshootingDiagnosisEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(diagnosisMapper).selectList(query.capture());
        String sql = query.getValue().getCustomSqlSegment().toLowerCase();
        assertTrue(sql.contains("investigation_mode"), sql);
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(
                InvestigationMode.SCENARIO_PLAYBOOK.name()));
        assertEquals(1, rows.size());
        assertEquals("diag-scenario", rows.getFirst().diagnosisId());
        assertEquals(InvestigationMode.SCENARIO_PLAYBOOK, rows.getFirst().investigationMode());
    }

    @Test
    void diagnosisSummaryReadsCurrentPersistedRouteSemanticsFromIndexedColumns() {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setDiagnosisId("diag-current");
        entity.setCaseId("case-current");
        entity.setSystem("CSDP");
        entity.setErrorCode("903001");
        entity.setService("csdp-wechat");
        entity.setStatus(DiagnosisStatus.READY_FOR_HUMAN.name());
        entity.setRehearsal(false);
        entity.setVersion(3);
        entity.setContractVersion(Diagnosis.CURRENT_CONTRACT_VERSION);
        entity.setInvestigationMode(InvestigationMode.SCENARIO_PLAYBOOK.name());
        entity.setRouteAuthority(RouteAuthority.RULE_MATCHED.name());
        entity.setPilotPlanVersion(7);

        DiagnosisSummary summary = DiagnosisSummary.from(entity);

        assertEquals(InvestigationMode.SCENARIO_PLAYBOOK, summary.investigationMode());
        assertEquals(RouteAuthority.RULE_MATCHED, summary.routeAuthority());
        assertEquals(RouteSemanticsProvenance.PERSISTED, summary.routeSemanticsProvenance());
        assertEquals(7, summary.pilotPlanVersion());
    }

    @Test
    void diagnosisSummaryTreatsLegacyContractsWithNullIndexedRouteSemanticsAsDerived() {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setDiagnosisId("diag-legacy");
        entity.setCaseId("case-legacy");
        entity.setSystem("CSDP");
        entity.setErrorCode("903001");
        entity.setService("csdp-wechat");
        entity.setStatus(DiagnosisStatus.READY_FOR_HUMAN.name());
        entity.setRehearsal(false);
        entity.setVersion(1);
        entity.setContractVersion("1.4");

        DiagnosisSummary summary = DiagnosisSummary.from(entity);

        assertEquals(null, summary.investigationMode());
        assertEquals(null, summary.routeAuthority());
        assertEquals(RouteSemanticsProvenance.LEGACY_DERIVED, summary.routeSemanticsProvenance());
    }

    @Test
    void diagnosisSummaryDirectConstructionRejectsMissingRouteSemanticsProvenance() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosisSummary(
                        "diag-current",
                        "case-current",
                        "CSDP",
                        "903001",
                        "csdp-wechat",
                        DiagnosisStatus.READY_FOR_HUMAN.name(),
                        InvestigationMode.ERROR_CODE_PLAYBOOK,
                        RouteAuthority.EXPLICIT,
                        null,
                        false,
                        null,
                        3,
                        null,
                        null));

        assertTrue(error.getMessage().contains("routeSemanticsProvenance"));
    }

    @Test
    void diagnosisSummaryDirectConstructionRejectsPersistedRowsMissingTypedRouteSemantics() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosisSummary(
                        "diag-current",
                        "case-current",
                        "CSDP",
                        "903001",
                        "csdp-wechat",
                        DiagnosisStatus.READY_FOR_HUMAN.name(),
                        InvestigationMode.ERROR_CODE_PLAYBOOK,
                        null,
                        RouteSemanticsProvenance.PERSISTED,
                        false,
                        null,
                        3,
                        null,
                        null));

        assertTrue(error.getMessage().contains("PERSISTED"));
    }

    @Test
    void diagnosisSummaryDirectConstructionRejectsLegacyDerivedRowsWithTypedRouteSemantics() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DiagnosisSummary(
                        "diag-legacy",
                        "case-legacy",
                        "CSDP",
                        "903001",
                        "csdp-wechat",
                        DiagnosisStatus.READY_FOR_HUMAN.name(),
                        InvestigationMode.ERROR_CODE_PLAYBOOK,
                        null,
                        RouteSemanticsProvenance.LEGACY_DERIVED,
                        false,
                        null,
                        1,
                        null,
                        null));

        assertTrue(error.getMessage().contains("LEGACY_DERIVED"));
    }

    @Test
    void diagnosisSummaryFailsClosedForCurrentRowsMissingIndexedRouteSemantics() {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setContractVersion(Diagnosis.CURRENT_CONTRACT_VERSION);
        entity.setInvestigationMode(InvestigationMode.ERROR_CODE_PLAYBOOK.name());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> DiagnosisSummary.from(entity));

        assertTrue(error.getMessage().contains("route semantics"));
    }

    @Test
    void diagnosisSummaryFailsClosedForLegacyRowsWithUnexpectedIndexedRouteSemantics() {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setContractVersion("1.4");
        entity.setInvestigationMode(InvestigationMode.ERROR_CODE_PLAYBOOK.name());
        entity.setRouteAuthority(RouteAuthority.EXPLICIT.name());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> DiagnosisSummary.from(entity));

        assertTrue(error.getMessage().contains("legacy"));
    }

    @Test
    void diagnosisSummaryFailsClosedForUnknownIndexedRouteSemanticsEnums() {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setContractVersion(Diagnosis.CURRENT_CONTRACT_VERSION);
        entity.setInvestigationMode("NOT_A_MODE");
        entity.setRouteAuthority(RouteAuthority.EXPLICIT.name());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> DiagnosisSummary.from(entity));

        assertTrue(error.getMessage().contains("investigationMode"));
    }

    @Test
    void optimisticConflictReturns409AndDoesNotEnqueue() {
        when(diagnosisMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> {
                    KnowledgeCandidate candidate = candidate();
                    service.updateAndEnqueue(7L, diagnosisWithCandidate(candidate), 2, candidate);
                });

        assertEquals(409, error.getCode());
        verify(outboxMapper, never()).insert(any(TroubleshootingKnowledgeOutboxEntity.class));
    }

    @Test
    void findsOneOutcomeCandidateByWorkspaceAndStableCandidateId() throws Exception {
        KnowledgeCandidate candidate = candidate();
        TroubleshootingKnowledgeOutboxEntity row =
                new TroubleshootingKnowledgeOutboxEntity();
        row.setWorkspaceId(7L);
        row.setCandidateId(candidate.candidateId());
        row.setPayloadJson(objectMapper.writeValueAsString(candidate));
        row.setDeleted(0);
        when(outboxMapper.selectOne(any())).thenReturn(row);

        KnowledgeCandidate found = service.findKnowledgeCandidate(
                7L, candidate.candidateId());

        assertEquals(candidate, found);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<TroubleshootingKnowledgeOutboxEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(outboxMapper).selectOne(query.capture());
        String sql = query.getValue().getCustomSqlSegment().toLowerCase();
        assertTrue(sql.contains("workspace"), sql);
        assertTrue(sql.contains("candidate"), sql);
        assertTrue(sql.contains("deleted"), sql);
        assertTrue(query.getValue().getParamNameValuePairs().values()
                .containsAll(List.of(7L, candidate.candidateId(), 0)));
    }

    @Test
    void closingAnIntakeDiagnosisSchedulesItsNotificationInTheAggregateTransaction() {
        when(diagnosisMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(diagnosisMapper.scheduleClosureNotification(anyLong(), any(), any())).thenReturn(1);
        Diagnosis closed = diagnosisWithCandidate(candidate());

        service.update(7L, closed, 2);

        verify(diagnosisMapper).scheduleClosureNotification(eq(7L), eq("diag-1"), any());
    }

    @Test
    void aNonClosedDiagnosisNeverSchedulesAClosureNotification() {
        when(diagnosisMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        service.update(7L, diagnosis(false), 2);

        verify(diagnosisMapper, never()).scheduleClosureNotification(anyLong(), any(), any());
    }

    private TroubleshootingDiagnosisEntity persisted(Diagnosis diagnosis, int version) throws Exception {
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setId(99L);
        entity.setWorkspaceId(7L);
        entity.setDiagnosisId(diagnosis.diagnosisId());
        entity.setAggregateJson(objectMapper.writeValueAsString(diagnosis));
        entity.setVersion(version);
        return entity;
    }

    private Diagnosis diagnosis(boolean rehearsal) {
        return diagnosis(
                rehearsal,
                DiagnosisStatus.READY_FOR_HUMAN,
                "summary",
                "root cause",
                Confidence.HIGH,
                false);
    }

    private Diagnosis diagnosis(
            boolean rehearsal,
            DiagnosisStatus status,
            String summary,
            String rootCause,
            Confidence confidence,
            boolean abstained) {
        IncidentContext incident = new IncidentContext(
                "inc-1",
                "CSDP",
                "csdp-wechat",
                "903001",
                "数据库访问异常",
                "P0",
                "待确认",
                null,
                Instant.parse("2026-07-25T01:02:00Z"),
                null,
                "manual",
                IncidentCompleteness.STRUCTURED,
                null);
        return Diagnosis.initial(
                "diag-1",
                "case-1",
                "run-1",
                incident,
                RouteMode.DETERMINISTIC,
                status,
                summary,
                rootCause,
                confidence,
                abstained,
                "csdp:903001",
                "903001 SOP",
                new PlaybookVersionRef("playbook-903001", 1),
                List.of(),
                List.of(),
                List.of(),
                null,
                rehearsal,
                true,
                List.of());
    }

    private Diagnosis legacyDiagnosis() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode json =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                        objectMapper.writeValueAsString(diagnosis(false)));
        json.put("contractVersion", "1.4");
        json.remove("investigationMode");
        json.remove("routeAuthority");
        json.remove("sourcePlaybookVersionRef");
        return objectMapper.treeToValue(json, Diagnosis.class);
    }

    private Diagnosis symptomDiagnosis(boolean rehearsal) {
        IncidentContext incident = new IncidentContext(
                "inc-symptom",
                "CSDP",
                "csdp-wechat",
                null,
                "会话消息发送失败",
                "P2",
                "待确认",
                "trace-safe-1",
                Instant.parse("2026-07-25T01:02:00Z"),
                null,
                "web:formal-workbench",
                IncidentCompleteness.SYMPTOM,
                null);
        return Diagnosis.initial(
                "diag-symptom",
                "case-symptom",
                "run-symptom",
                incident,
                RouteMode.LLM_FALLBACK,
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "证据不足",
                "待补证",
                Confidence.LOW,
                true,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                rehearsal,
                true,
                List.of());
    }

    private Diagnosis scenarioDiagnosis(boolean rehearsal) {
        IncidentContext incident = new IncidentContext(
                "inc-symptom",
                "CSDP",
                "csdp-wechat",
                null,
                "会话消息发送失败",
                "P2",
                "待确认",
                "trace-safe-1",
                Instant.parse("2026-07-25T01:02:00Z"),
                null,
                "web:deployment-topology-scenario",
                IncidentCompleteness.STRUCTURED,
                null);
        return Diagnosis.initial(
                "diag-scenario",
                "case-scenario",
                "run-scenario",
                incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.INSUFFICIENT_EVIDENCE,
                NorthStarTimings.concluded(
                        Instant.parse("2026-07-25T01:02:00Z"),
                        Instant.parse("2026-07-25T01:02:01Z"),
                        Instant.parse("2026-07-25T01:02:01Z")),
                DiagnosisStatus.NEEDS_INVESTIGATION,
                "场景已创建",
                "待取得拓扑证据",
                Confidence.LOW,
                true,
                "csdp:scenario:deployment_topology_probe",
                "部署拓扑拨测",
                new PlaybookVersionRef("playbook-topology", 3),
                List.of(),
                List.of(),
                List.of(),
                null,
                rehearsal,
                true,
                List.of(),
                List.of());
    }

    private KnowledgeCandidate candidate() {
        return new KnowledgeCandidate(
                "candidate-1",
                KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                "diag-1",
                "case-1",
                "run-1",
                "CSDP",
                "903001",
                "csdp:903001",
                "root cause",
                List.of(),
                List.of(),
                List.of(),
                "resolved",
                null,
                "on-call",
                Instant.parse("2026-07-25T02:00:00Z"),
                new KnowledgeCandidate.OutcomeProof(
                        ClosureOutcome.UNRESOLVED,
                        false,
                        "on-call",
                        Instant.parse("2026-07-25T02:00:00Z")),
                null);
    }

    private Diagnosis diagnosisWithCandidate(KnowledgeCandidate candidate) {
        DiagnosisStateMachine machine = new DiagnosisStateMachine(
                Clock.fixed(Instant.parse("2026-07-25T02:00:00Z"), ZoneOffset.UTC),
                prefix -> prefix.equals("candidate") ? candidate.candidateId() : prefix + "-1");
        Diagnosis confirmed = machine.confirm(diagnosis(false), "on-call");
        return machine.close(
                confirmed,
                ClosureOutcome.UNRESOLVED,
                candidate.resolutionSummary(),
                false,
                candidate.feedback(),
                true,
                candidate.createdBy());
    }
}
