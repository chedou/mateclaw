package vip.mate.troubleshooting.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookCandidateEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookCandidateMapper;
import vip.mate.troubleshooting.synthesis.MybatisPlaybookCandidateStore;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.synthesis.PlaybookCandidateStore;
import vip.mate.troubleshooting.synthesis.PlaybookDraft;
import vip.mate.troubleshooting.synthesis.PlaybookKnowledgeRecord;
import vip.mate.troubleshooting.synthesis.ReferenceSolutionComparator;

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

class MybatisPlaybookCandidateStoreTest {

    private static final Instant REPORTED = Instant.parse("2026-07-20T09:12:00Z");
    private static final Instant READY = Instant.parse("2026-07-20T09:13:00Z");
    private static final Instant CONCLUDED = Instant.parse("2026-07-20T09:13:05Z");

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TroubleshootingPlaybookCandidateEntity.class);
    }

    @Test
    void roundTripsTheFullCandidateAndUsesGenerationKeyForIdempotency() {
        TroubleshootingPlaybookCandidateMapper mapper =
                mock(TroubleshootingPlaybookCandidateMapper.class);
        AtomicReference<TroubleshootingPlaybookCandidateEntity> row = new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(call -> row.get());
        when(mapper.insert(any(TroubleshootingPlaybookCandidateEntity.class))).thenAnswer(call -> {
            TroubleshootingPlaybookCandidateEntity entity = call.getArgument(0);
            entity.setId(1L);
            row.set(entity);
            return 1;
        });
        MybatisPlaybookCandidateStore store = new MybatisPlaybookCandidateStore(
                mapper, new ObjectMapper().findAndRegisterModules());

        PlaybookCandidateStore.StoredCandidate first = store.saveOrGet(1L, candidate());
        PlaybookCandidateStore.StoredCandidate retry = store.saveOrGet(1L, candidate());

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.candidate()).isEqualTo(first.candidate());
        assertThat(retry.candidate().fixtureMode()).isTrue();
        assertThat(retry.candidate().draft().evidenceCitations())
                .containsExactly("SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE");
        assertThat(retry.candidate().timings().handoffAt()).isNull();
        assertThat(retry.candidate().timings().adoptCost()).isNull();
        assertThat(row.get().getReviewStatus()).isEqualTo("CANDIDATE");
        assertThat(row.get().getValidationStatus()).isEqualTo("VALID");
        assertThat(row.get().getService()).isEqualTo("csdp-session-service");
        verify(mapper, times(1)).insert(any(TroubleshootingPlaybookCandidateEntity.class));
    }

    @Test
    void domainRecordCannotBeConstructedAsApproved() {
        PlaybookKnowledgeRecord candidate = candidate();

        assertThatThrownBy(() -> new PlaybookKnowledgeRecord(
                candidate.recordId(), candidate.draft(), candidate.origin(),
                "APPROVED", candidate.validationStatus(), "reviewer", "force approve",
                candidate.evidenceBundleId(), candidate.service(),
                candidate.referenceComparison(), "NOT_ELIGIBLE",
                candidate.eligibilityReasons(), candidate.fixtureMode(),
                candidate.timings(), candidate.createdAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidate");
    }

    @Test
    void domainRecordCannotDropTheFixtureMarkerDuringP1() {
        PlaybookKnowledgeRecord candidate = candidate();

        assertThatThrownBy(() -> new PlaybookKnowledgeRecord(
                candidate.recordId(), candidate.draft(), candidate.origin(),
                candidate.reviewStatus(), candidate.validationStatus(), "", "",
                candidate.evidenceBundleId(), candidate.service(),
                candidate.referenceComparison(), candidate.approvalEligibility(),
                candidate.eligibilityReasons(), false,
                candidate.timings(), candidate.createdAt()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixture");
    }

    @Test
    void duplicateRecoveryDoesNotRunInsideAnAbortedDatabaseTransaction() throws Exception {
        Transactional boundary = MybatisPlaybookCandidateStore.class
                .getMethod("saveOrGet", long.class, PlaybookKnowledgeRecord.class)
                .getAnnotation(Transactional.class);

        assertThat(boundary)
                .isNotNull()
                .extracting(Transactional::propagation)
                .as("the post-conflict lookup must start outside the failed insert transaction")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void returnsTheWinningCandidateAfterAUniqueKeyRace() {
        TroubleshootingPlaybookCandidateMapper mapper =
                mock(TroubleshootingPlaybookCandidateMapper.class);
        AtomicReference<TroubleshootingPlaybookCandidateEntity> winningRow =
                new AtomicReference<>();
        when(mapper.selectOne(any()))
                .thenReturn(null)
                .thenAnswer(call -> winningRow.get());
        when(mapper.insert(any(TroubleshootingPlaybookCandidateEntity.class)))
                .thenAnswer(call -> {
                    winningRow.set(call.getArgument(0));
                    throw new DataIntegrityViolationException("simulated unique-key race");
                });
        MybatisPlaybookCandidateStore store = new MybatisPlaybookCandidateStore(
                mapper, new ObjectMapper().findAndRegisterModules());

        PlaybookCandidateStore.StoredCandidate result = store.saveOrGet(1L, candidate());

        assertThat(result.created()).isFalse();
        assertThat(result.candidate()).isEqualTo(candidate());
    }

    @Test
    void listsPersistedReviewCandidatesWithoutChangingTheirQualificationState()
            throws Exception {
        TroubleshootingPlaybookCandidateMapper mapper =
                mock(TroubleshootingPlaybookCandidateMapper.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PlaybookKnowledgeRecord candidate = candidate();
        TroubleshootingPlaybookCandidateEntity row =
                new TroubleshootingPlaybookCandidateEntity();
        row.setWorkspaceId(7L);
        row.setRecordId(candidate.recordId());
        row.setReviewStatus(candidate.reviewStatus());
        row.setAggregateJson(objectMapper.writeValueAsString(candidate));
        row.setDeleted(0);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        MybatisPlaybookCandidateStore store = new MybatisPlaybookCandidateStore(
                mapper, objectMapper);

        List<PlaybookKnowledgeRecord> result = store.list(7L, 500);

        assertThat(result).containsExactly(candidate);
        assertThat(result.getFirst().reviewStatus()).isEqualTo("CANDIDATE");
        assertThat(result.getFirst().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<TroubleshootingPlaybookCandidateEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getCustomSqlSegment())
                .contains("workspace_id")
                .contains("deleted")
                .contains("ORDER BY id DESC")
                .endsWith("LIMIT 200");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains(7L, 0);
    }

    @Test
    void findsOneReviewCandidateByWorkspaceAndStableRecordId() throws Exception {
        TroubleshootingPlaybookCandidateMapper mapper =
                mock(TroubleshootingPlaybookCandidateMapper.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PlaybookKnowledgeRecord candidate = candidate();
        TroubleshootingPlaybookCandidateEntity row =
                new TroubleshootingPlaybookCandidateEntity();
        row.setWorkspaceId(7L);
        row.setRecordId(candidate.recordId());
        row.setAggregateJson(objectMapper.writeValueAsString(candidate));
        row.setDeleted(0);
        when(mapper.selectOne(any())).thenReturn(row);
        MybatisPlaybookCandidateStore store = new MybatisPlaybookCandidateStore(
                mapper, objectMapper);

        PlaybookKnowledgeRecord found = store.find(7L, candidate.recordId());

        assertThat(found).isEqualTo(candidate);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<TroubleshootingPlaybookCandidateEntity>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        assertThat(query.getValue().getCustomSqlSegment())
                .contains("workspace_id")
                .contains("record_id")
                .contains("deleted");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains(7L, candidate.recordId(), 0);
    }

    private PlaybookKnowledgeRecord candidate() {
        PlaybookDraft draft = new PlaybookDraft(
                "draft-012345678901234567890123",
                "0".repeat(64),
                "incident-message-send-001",
                "SCENARIO",
                new PlaybookDraft.ProposedSelector("CSDP", "message_send_failed", null),
                "会话消息发送失败排查草案",
                List.of(new PlaybookDraft.EvidencePlanStep(
                        "locate_failed_request", "log_search", "定位失败请求", true)),
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict", "状态冲突", List.of("log_trace_bundle"),
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict", "会话状态写冲突",
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "verify_recovery", "由研发在平台外验证恢复",
                        "EXTERNAL_HUMAN", List.of("SYNTH-LOG-SEARCH"))),
                List.of("SYNTH-LOG-SEARCH", "SYNTH-TRACE-BUNDLE"),
                new PlaybookDraft.ModelProvenance(
                        "openai", "fixed", "7:v1", PlaybookDraft.CONTRACT_VERSION,
                        CONCLUDED, 1),
                false,
                List.of());
        return new PlaybookKnowledgeRecord(
                "candidate-012345678901234567890123",
                draft,
                "EVIDENCE_DERIVED",
                "CANDIDATE",
                "VALID",
                "",
                "",
                "evidence-bundle-01234567890123456789012345678901",
                "csdp-session-service",
                new ReferenceSolutionComparator.Comparison(
                        "reference-message-send-failure/v1", false, 0.4,
                        List.of("compare_success_sample"), List.of(), List.of(),
                        List.of("contrast_sample")),
                "NOT_ELIGIBLE",
                List.of("P1_CALIBRATION_PERIOD", "CONTRAST_UNAVAILABLE"),
                true,
                NorthStarTimings.concluded(REPORTED, READY, CONCLUDED),
                CONCLUDED);
    }
}
