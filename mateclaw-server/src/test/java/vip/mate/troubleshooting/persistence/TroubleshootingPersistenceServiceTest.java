package vip.mate.troubleshooting.persistence;

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
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TroubleshootingPersistenceServiceTest {

    @Mock private TroubleshootingDiagnosisMapper diagnosisMapper;
    @Mock private TroubleshootingKnowledgeOutboxMapper outboxMapper;

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
        service = new TroubleshootingPersistenceService(diagnosisMapper, outboxMapper, objectMapper);
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
        assertEquals(0, stored.version());
        assertTrue(stored.created());
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
        assertEquals("knowledge-candidate.v1", outbox.getValue().getContractVersion());
        assertEquals(7L, outbox.getValue().getWorkspaceId());
        assertEquals(KnowledgePublicationStatus.PENDING, outbox.getValue().getStatus());
        assertTrue(outbox.getValue().getPayloadJson().contains("candidate-1"));
        assertEquals(3, updated.version());
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
                List.of(),
                List.of(),
                List.of(),
                null,
                rehearsal,
                true,
                List.of());
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
                Instant.parse("2026-07-25T02:00:00Z"));
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
