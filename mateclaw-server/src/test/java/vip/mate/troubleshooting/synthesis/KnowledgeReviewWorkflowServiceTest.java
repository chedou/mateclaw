package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeReviewEntity;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeReviewMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeReviewWorkflowServiceTest {

    @Test
    void startsAWorkspaceScopedReviewWithAnAuditableSourceSnapshot() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(null);
        when(sources.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(source()));
        when(mapper.insert(any(TroubleshootingKnowledgeReviewEntity.class)))
                .thenReturn(1);

        KnowledgeReviewState state = service.start(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                0,
                "reviewer-a",
                "核对固定回放与引用");

        assertThat(state.status()).isEqualTo(KnowledgeReviewStatus.IN_REVIEW);
        assertThat(state.version()).isEqualTo(1);
        assertThat(state.reviewer()).isEqualTo("reviewer-a");
        assertThat(state.reason()).isEqualTo("核对固定回放与引用");
        assertThat(state.selectorKey()).isEqualTo("csdp:scenario:message_send_failed");
        assertThat(state.snapshot().validationStatus()).isEqualTo("VALID");
        assertThat(state.snapshot().modelConfigVersion()).isEqualTo("model-config-v7");
        assertThat(state.snapshot().referenceComparison().referenceId())
                .isEqualTo("reference-1");
        assertThat(state.snapshot().fixtureMode()).isTrue();

        ArgumentCaptor<TroubleshootingKnowledgeReviewEntity> inserted =
                ArgumentCaptor.forClass(TroubleshootingKnowledgeReviewEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getWorkspaceId()).isEqualTo(7L);
        assertThat(inserted.getValue().getOrigin()).isEqualTo("EVIDENCE_DERIVED");
        assertThat(inserted.getValue().getSourceRecordId()).isEqualTo("record-1");
        assertThat(inserted.getValue().getStatus()).isEqualTo("IN_REVIEW");
        assertThat(inserted.getValue().getVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getSnapshotJson())
                .contains("model-config-v7")
                .doesNotContain("searchTerm", "rawLog", "credential");
    }

    @Test
    void refusesToStartAReviewWhenTheSourceDoesNotExistInTheWorkspace() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        when(mapper.findBySource(8L, "OUTCOME_BACKED", "candidate-1"))
                .thenReturn(null);
        when(sources.find(8L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(
                8L,
                KnowledgeOrigin.OUTCOME_BACKED,
                "candidate-1",
                0,
                "reviewer-a",
                "核对关闭结果"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(404);
        verify(mapper, never()).insert(
                any(TroubleshootingKnowledgeReviewEntity.class));
    }

    @Test
    void refusesCredentialsOrRawDeveloperEvidenceInThePersistedReviewReason() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);

        assertThatThrownBy(() -> service.start(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                0,
                "reviewer-a",
                "password=super-secret"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);
        assertThatThrownBy(() -> service.start(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                0,
                "reviewer-a",
                "请粘贴原始日志后再审核"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);
        verify(mapper, never()).insert(
                any(TroubleshootingKnowledgeReviewEntity.class));
    }

    @Test
    void rejectsOnlyTheExactInReviewVersionAndAdvancesTheOptimisticVersion() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        TroubleshootingKnowledgeReviewEntity current = persisted("IN_REVIEW", 1);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(current);
        when(mapper.transition(
                eq(7L),
                eq("review-1"),
                eq("IN_REVIEW"),
                eq("REJECTED"),
                eq(1),
                eq("reviewer-b"),
                eq("缺少负例回放"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        KnowledgeReviewState rejected = service.reject(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                1,
                "reviewer-b",
                "缺少负例回放");

        assertThat(rejected.status()).isEqualTo(KnowledgeReviewStatus.REJECTED);
        assertThat(rejected.version()).isEqualTo(2);
        assertThat(rejected.reviewer()).isEqualTo("reviewer-b");
        assertThat(rejected.reason()).isEqualTo("缺少负例回放");
    }

    @Test
    void staleReviewDecisionFailsClosedWithoutUpdatingTheLedger() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        when(mapper.findBySource(7L, "MANUAL", "sop-1"))
                .thenReturn(persisted("IN_REVIEW", 2));

        assertThatThrownBy(() -> service.reject(
                7L,
                KnowledgeOrigin.MANUAL,
                "sop-1",
                1,
                "reviewer-a",
                "合同回放不完整"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);
        verify(mapper, never()).transition(
                anyLong(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void listsReviewStatesForTheExactInboxSourcesInsteadOfARecentGlobalSlice() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        TroubleshootingKnowledgeReviewEntity outcome = persisted("IN_REVIEW", 1);
        outcome.setOrigin("OUTCOME_BACKED");
        outcome.setSourceRecordId("candidate-1");
        when(mapper.listBySources(eq(7L), any()))
                .thenReturn(List.of(outcome));
        List<KnowledgeReviewSourceKey> requested = List.of(
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"),
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.OUTCOME_BACKED, "candidate-1"),
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.MANUAL, "sop-1"));

        List<KnowledgeReviewState> states = service.listForSources(7L, requested);

        assertThat(states).extracting(KnowledgeReviewState::sourceRecordId)
                .containsExactly("candidate-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeReviewSourceKey>> keys =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).listBySources(eq(7L), keys.capture());
        assertThat(keys.getValue()).containsExactlyElementsOf(requested);
    }

    private KnowledgeReviewWorkflowService service(
            TroubleshootingKnowledgeReviewMapper mapper,
            KnowledgeReviewSourceReader sources) {
        return new KnowledgeReviewWorkflowService(
                mapper, sources, new ObjectMapper().findAndRegisterModules());
    }

    private KnowledgeReviewSource source() {
        ReferenceSolutionComparator.Comparison comparison =
                new ReferenceSolutionComparator.Comparison(
                        "reference-1", true, 1.0,
                        List.of(), List.of(), List.of(), List.of());
        KnowledgeReviewSnapshot snapshot = new KnowledgeReviewSnapshot(
                "VALID",
                List.of(),
                comparison,
                "model-config-v7",
                "NOT_ELIGIBLE",
                List.of("FIXTURE_ONLY"),
                true);
        return new KnowledgeReviewSource(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                "csdp:scenario:message_send_failed",
                snapshot);
    }

    private TroubleshootingKnowledgeReviewEntity persisted(String status, int version) {
        TroubleshootingKnowledgeReviewEntity entity =
                new TroubleshootingKnowledgeReviewEntity();
        entity.setId(1L);
        entity.setWorkspaceId(7L);
        entity.setReviewId("review-1");
        entity.setOrigin("EVIDENCE_DERIVED");
        entity.setSourceRecordId("record-1");
        entity.setSelectorKey("csdp:scenario:message_send_failed");
        entity.setStatus(status);
        entity.setReviewer("reviewer-a");
        entity.setReason("核对固定回放与引用");
        try {
            entity.setSnapshotJson(new ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(source().snapshot()));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        entity.setVersion(version);
        entity.setDeleted(0);
        entity.setCreateTime(LocalDateTime.parse("2026-07-29T10:00:00"));
        entity.setUpdateTime(LocalDateTime.parse("2026-07-29T10:00:00"));
        return entity;
    }
}
