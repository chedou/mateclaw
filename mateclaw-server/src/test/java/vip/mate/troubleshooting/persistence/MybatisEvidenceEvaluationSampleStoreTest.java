package vip.mate.troubleshooting.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSample;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleStore;
import vip.mate.troubleshooting.evaluation.MybatisEvidenceEvaluationSampleStore;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceEvaluationSampleEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceEvaluationSampleMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisEvidenceEvaluationSampleStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(
                assistant, TroubleshootingEvidenceEvaluationSampleEntity.class);
    }

    @Test
    void roundTripsTheSafeProjectionAndUsesSampleKeyForIdempotency() {
        TroubleshootingEvidenceEvaluationSampleMapper mapper =
                mock(TroubleshootingEvidenceEvaluationSampleMapper.class);
        AtomicReference<TroubleshootingEvidenceEvaluationSampleEntity> row =
                new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(call -> row.get());
        when(mapper.insert(any(TroubleshootingEvidenceEvaluationSampleEntity.class)))
                .thenAnswer(call -> {
                    TroubleshootingEvidenceEvaluationSampleEntity entity = call.getArgument(0);
                    entity.setId(1L);
                    row.set(entity);
                    return 1;
                });
        MybatisEvidenceEvaluationSampleStore store = store(mapper);

        EvidenceEvaluationSampleStore.StoredSample first = store.saveOrGet(7L, sample());
        EvidenceEvaluationSampleStore.StoredSample retry = store.saveOrGet(7L, sample());

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.sample()).isEqualTo(first.sample());
        assertThat(row.get().getEvidenceStage()).isEqualTo("FULL_SPINE_OBSERVED");
        assertThat(row.get().getReferenceStatus()).isEqualTo("EVIDENCE_CAPTURED");
        assertThat(row.get().getFixtureMode()).isFalse();
        assertThat(row.get().getDiagnosisFixtureMode()).isTrue();
        assertThat(row.get().getCaptureIdentityKey()).isEqualTo("a".repeat(64));
        assertThat(row.get().getCaptureRevision()).isEqualTo(1);
        assertThat(row.get().getAggregateJson())
                .doesNotContain("source_lookup_key", "runtime-secret", "L::logs");
        verify(mapper, times(1))
                .insert(any(TroubleshootingEvidenceEvaluationSampleEntity.class));
    }

    @Test
    void finalizationUsesOptimisticVersionAndReturnsTheUpdatedAggregate() {
        TroubleshootingEvidenceEvaluationSampleMapper mapper =
                mock(TroubleshootingEvidenceEvaluationSampleMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        MybatisEvidenceEvaluationSampleStore store = store(mapper);
        EvidenceEvaluationSample finalized = sample().finalizeReference(
                new vip.mate.troubleshooting.synthesis.ReferenceSolution(
                        "eval-012345678901234567890123/reference/v1",
                        "message_send_failed",
                        List.of("locate_failed_request", "trace_ps_id"),
                        List.of("restart_production"),
                        List.of(new vip.mate.troubleshooting.synthesis.ReferenceSolution.OrderingConstraint(
                                "locate_failed_request", "trace_ps_id")),
                        List.of("log_search", "log_trace_bundle", "contrast_sample")),
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        vip.mate.troubleshooting.model.ClosureOutcome.RECOVERED,
                        "人工恢复后验证通过",
                        true,
                        NOW),
                "reviewer",
                NOW.plusSeconds(5));

        EvidenceEvaluationSample result = store.finalizeReference(7L, finalized, 0);

        assertThat(result).isEqualTo(finalized);
        assertThat(result.version()).isEqualTo(1);
        verify(mapper).update(any(), any());
    }

    @Test
    void rejectsAStaleFinalizationVersion() {
        TroubleshootingEvidenceEvaluationSampleMapper mapper =
                mock(TroubleshootingEvidenceEvaluationSampleMapper.class);
        when(mapper.update(any(), any())).thenReturn(0);
        MybatisEvidenceEvaluationSampleStore store = store(mapper);
        EvidenceEvaluationSample finalized = sample().finalizeReference(
                new vip.mate.troubleshooting.synthesis.ReferenceSolution(
                        "eval-012345678901234567890123/reference/v1",
                        "message_send_failed",
                        List.of("locate_failed_request"),
                        List.of("restart_production"),
                        List.of(),
                        List.of("log_search", "log_trace_bundle", "contrast_sample")),
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        vip.mate.troubleshooting.model.ClosureOutcome.UNRESOLVED,
                        "仍需人工跟进",
                        false,
                        NOW),
                "reviewer",
                NOW.plusSeconds(5));

        assertThatThrownBy(() -> store.finalizeReference(7L, finalized, 0))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("version conflict");
    }

    @Test
    void duplicateRecoveryRunsOutsideAnAbortedDatabaseTransaction() throws Exception {
        Transactional boundary = MybatisEvidenceEvaluationSampleStore.class
                .getMethod("saveOrGet", long.class, EvidenceEvaluationSample.class)
                .getAnnotation(Transactional.class);

        assertThat(boundary)
                .isNotNull()
                .extracting(Transactional::propagation)
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void returnsTheWinningSampleAfterAUniqueKeyRace() {
        TroubleshootingEvidenceEvaluationSampleMapper mapper =
                mock(TroubleshootingEvidenceEvaluationSampleMapper.class);
        AtomicReference<TroubleshootingEvidenceEvaluationSampleEntity> winner =
                new AtomicReference<>();
        when(mapper.selectOne(any()))
                .thenReturn(null)
                .thenAnswer(call -> winner.get());
        when(mapper.insert(any(TroubleshootingEvidenceEvaluationSampleEntity.class)))
                .thenAnswer(call -> {
                    winner.set(call.getArgument(0));
                    throw new DataIntegrityViolationException("simulated unique-key race");
                });

        EvidenceEvaluationSampleStore.StoredSample result =
                store(mapper).saveOrGet(7L, sample());

        assertThat(result.created()).isFalse();
        assertThat(result.sample()).isEqualTo(sample());
    }

    @Test
    void returnsTheLatestImmutableCaptureRevisionForAnIdentity() {
        TroubleshootingEvidenceEvaluationSampleMapper mapper =
                mock(TroubleshootingEvidenceEvaluationSampleMapper.class);
        TroubleshootingEvidenceEvaluationSampleEntity latest =
                new TroubleshootingEvidenceEvaluationSampleEntity();
        latest.setAggregateJson(new ObjectMapper().findAndRegisterModules()
                .valueToTree(sample()).toString());
        latest.setCaptureRevision(3);
        when(mapper.selectOne(any())).thenReturn(latest);

        EvidenceEvaluationSample result = store(mapper)
                .findLatestByCaptureIdentity(7L, "a".repeat(64))
                .orElseThrow();

        assertThat(result).isEqualTo(sample());
        verify(mapper).selectOne(any());
    }

    private MybatisEvidenceEvaluationSampleStore store(
            TroubleshootingEvidenceEvaluationSampleMapper mapper) {
        return new MybatisEvidenceEvaluationSampleStore(
                mapper, new ObjectMapper().findAndRegisterModules());
    }

    private EvidenceEvaluationSample sample() {
        return EvidenceEvaluationSample.captured(
                "eval-012345678901234567890123",
                "a".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                new GuanceEvidenceSpinePreview(
                        GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                        new GuanceEvidenceReadiness(
                                "CSDP", "session-svc",
                                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                                true, true,
                                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                                true, List.of(), List.of()),
                        4L,
                        "ps-message-001",
                        3,
                        List.of("gateway", "session-svc", "openim"),
                        2,
                        42L,
                        new GuanceEvidenceSpinePreview.Contrast(
                                true, "session_state_conflict",
                                100, 92, 100, 3, 0.92, 0.03, 0.89),
                        3,
                        50L,
                        List.of(
                                observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                                observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                                observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                        NOW,
                        List.of()),
                true,
                "admin",
                NOW);
    }

    private GuanceEvidenceSpinePreview.Step observed(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                ref,
                NOW);
    }
}
