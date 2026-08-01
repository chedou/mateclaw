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
import vip.mate.troubleshooting.evaluation.BaselineEvaluationRun;
import vip.mate.troubleshooting.evaluation.BaselineEvaluationRunStore;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSample;
import vip.mate.troubleshooting.evaluation.MybatisBaselineEvaluationRunStore;
import vip.mate.troubleshooting.model.TroubleshootingBaselineEvaluationRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingBaselineEvaluationRunMapper;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisBaselineEvaluationRunStoreTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(
                assistant, TroubleshootingBaselineEvaluationRunEntity.class);
    }

    @Test
    void claimsBeforeWorkCompletesWithTheOwnedTokenAndThenReusesTheResult() throws Exception {
        TroubleshootingBaselineEvaluationRunMapper mapper =
                mock(TroubleshootingBaselineEvaluationRunMapper.class);
        AtomicReference<TroubleshootingBaselineEvaluationRunEntity> row =
                new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(call -> row.get());
        when(mapper.insert(any(TroubleshootingBaselineEvaluationRunEntity.class)))
                .thenAnswer(call -> {
                    TroubleshootingBaselineEvaluationRunEntity entity = call.getArgument(0);
                    entity.setId(1L);
                    row.set(entity);
                    return 1;
                });
        when(mapper.update(any(), any())).thenReturn(1);
        MybatisBaselineEvaluationRunStore store = store(mapper);

        BaselineEvaluationRunStore.ClaimResult claimed = store.claim(7L, claim());
        BaselineEvaluationRunStore.StoredRun completed = store.complete(7L, claim(), run());
        row.get().setRunStatus("SCORED");
        row.get().setResultJson(JSON.writeValueAsString(run()));
        BaselineEvaluationRunStore.ClaimResult retry = store.claim(7L, retryClaim());

        assertThat(claimed.state()).isEqualTo(BaselineEvaluationRunStore.ClaimState.ACQUIRED);
        assertThat(completed.created()).isTrue();
        assertThat(retry.state()).isEqualTo(BaselineEvaluationRunStore.ClaimState.COMPLETED);
        assertThat(retry.completedRun()).isEqualTo(run());
        assertThat(row.get().getRunStatus()).isEqualTo("SCORED");
        assertThat(row.get().getEvidenceFixtureMode()).isFalse();
        assertThat(row.get().getDiagnosisFixtureMode()).isFalse();
        assertThat(row.get().getClaimToken()).isEqualTo("claim-token-0001");
        assertThat(row.get().getResultJson())
                .doesNotContain("source_lookup_key", "raw log", "L::logs", "passed");
        verify(mapper, times(1))
                .insert(any(TroubleshootingBaselineEvaluationRunEntity.class));
    }

    @Test
    void aUniqueKeyRaceReturnsInProgressWithoutASecondWorkerClaim() {
        TroubleshootingBaselineEvaluationRunMapper mapper =
                mock(TroubleshootingBaselineEvaluationRunMapper.class);
        AtomicReference<TroubleshootingBaselineEvaluationRunEntity> winner =
                new AtomicReference<>();
        when(mapper.selectOne(any()))
                .thenReturn(null)
                .thenAnswer(call -> winner.get());
        when(mapper.insert(any(TroubleshootingBaselineEvaluationRunEntity.class)))
                .thenAnswer(call -> {
                    TroubleshootingBaselineEvaluationRunEntity row = call.getArgument(0);
                    row.setClaimToken("winning-token");
                    row.setReservationExpiresAt(
                            LocalDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC));
                    winner.set(row);
                    throw new DataIntegrityViolationException("simulated unique-key race");
                });

        BaselineEvaluationRunStore.ClaimResult result = store(mapper).claim(7L, claim());

        assertThat(result.state()).isEqualTo(
                BaselineEvaluationRunStore.ClaimState.IN_PROGRESS);
        verify(mapper, times(0)).update(any(), any());
    }

    @Test
    void anExpiredLeaseCanBeTakenOverWithCompareAndSet() {
        TroubleshootingBaselineEvaluationRunMapper mapper =
                mock(TroubleshootingBaselineEvaluationRunMapper.class);
        TroubleshootingBaselineEvaluationRunEntity expired = new TroubleshootingBaselineEvaluationRunEntity();
        expired.setWorkspaceId(7L);
        expired.setRunKey("a".repeat(64));
        expired.setRunStatus("RUNNING");
        expired.setClaimToken("old-token");
        expired.setReservationExpiresAt(
                LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        expired.setDeleted(0);
        when(mapper.selectOne(any())).thenReturn(expired);
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(store(mapper).claim(7L, claim()).state())
                .isEqualTo(BaselineEvaluationRunStore.ClaimState.ACQUIRED);
        verify(mapper).update(any(), any());
    }

    @Test
    void heartbeatRenewsOnlyTheClaimTokenStillOwnedByThisWorker() {
        TroubleshootingBaselineEvaluationRunMapper mapper =
                mock(TroubleshootingBaselineEvaluationRunMapper.class);
        when(mapper.update(any(), any())).thenReturn(1, 0);
        MybatisBaselineEvaluationRunStore store = store(mapper);

        assertThat(store.renew(7L, claim(), NOW.plusSeconds(600))).isTrue();
        assertThat(store.renew(7L, claim(), NOW.plusSeconds(700))).isFalse();

        verify(mapper, times(2)).update(any(), any());
    }

    @Test
    void claimCompletionAndReleaseRunOutsideAnAbortedDatabaseTransaction() throws Exception {
        for (String method : List.of("claim", "complete", "renew", "release")) {
            Class<?>[] parameters = switch (method) {
                case "claim", "release" -> new Class<?>[] {
                        long.class, BaselineEvaluationRunStore.RunClaim.class};
                case "renew" -> new Class<?>[] {
                        long.class,
                        BaselineEvaluationRunStore.RunClaim.class,
                        Instant.class};
                default -> new Class<?>[] {
                        long.class,
                        BaselineEvaluationRunStore.RunClaim.class,
                        BaselineEvaluationRun.class};
            };
            Transactional boundary = MybatisBaselineEvaluationRunStore.class
                    .getMethod(method, parameters)
                    .getAnnotation(Transactional.class);
            assertThat(boundary)
                    .isNotNull()
                    .extracting(Transactional::propagation)
                    .isEqualTo(Propagation.NOT_SUPPORTED);
        }
    }

    @Test
    void transactionalStoreRemainsProxyableByTheSpringRuntime() {
        assertThat(Modifier.isFinal(
                MybatisBaselineEvaluationRunStore.class.getModifiers()))
                .isFalse();
    }

    private MybatisBaselineEvaluationRunStore store(
            TroubleshootingBaselineEvaluationRunMapper mapper) {
        return new MybatisBaselineEvaluationRunStore(mapper, JSON);
    }

    private BaselineEvaluationRunStore.RunClaim claim() {
        return new BaselineEvaluationRunStore.RunClaim(
                "baseline-012345678901234567890123",
                "a".repeat(64),
                "eval-012345678901234567890123",
                "diag-1",
                1,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                false,
                false,
                "openai",
                "fixed-model",
                "7:model-config-v1",
                "claim-token-0001",
                NOW,
                NOW.plusSeconds(300));
    }

    private BaselineEvaluationRunStore.RunClaim retryClaim() {
        BaselineEvaluationRunStore.RunClaim first = claim();
        return new BaselineEvaluationRunStore.RunClaim(
                first.runId(),
                first.runKey(),
                first.sampleId(),
                first.diagnosisId(),
                first.sampleVersion(),
                first.sourcePlatform(),
                first.evidenceFixtureMode(),
                first.diagnosisFixtureMode(),
                first.modelProvider(),
                first.modelName(),
                first.modelConfigVersion(),
                "claim-token-0002",
                NOW.plusSeconds(1),
                NOW.plusSeconds(301));
    }

    private BaselineEvaluationRun run() {
        return new BaselineEvaluationRun(
                "baseline-012345678901234567890123",
                "a".repeat(64),
                "eval-012345678901234567890123",
                "diag-1",
                1,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                false,
                false,
                "b".repeat(64),
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        BaselineEvaluationRun.Classification.HELPFUL,
                        true,
                        1.0,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false),
                new BaselineEvaluationRun.ModelSnapshot(
                        "openai",
                        "fixed-model",
                        "7:model-config-v1",
                        NOW,
                        1,
                        320L,
                        160L,
                        480L),
                50,
                150,
                200,
                "reviewer",
                NOW);
    }
}
