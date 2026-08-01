package vip.mate.troubleshooting.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.troubleshooting.model.TroubleshootingManualPlaybookReplayEntity;
import vip.mate.troubleshooting.repository.TroubleshootingManualPlaybookReplayMapper;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayAttestation;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayAttestationStore;
import vip.mate.troubleshooting.synthesis.MybatisManualPlaybookReplayAttestationStore;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MybatisManualPlaybookReplayAttestationStoreTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(
                assistant, TroubleshootingManualPlaybookReplayEntity.class);
    }

    @Test
    void roundTripsAndReusesTheExactCandidateAndSuiteProof() {
        TroubleshootingManualPlaybookReplayMapper mapper =
                mock(TroubleshootingManualPlaybookReplayMapper.class);
        AtomicReference<TroubleshootingManualPlaybookReplayEntity> row =
                new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(invocation -> row.get());
        when(mapper.insert(any(TroubleshootingManualPlaybookReplayEntity.class)))
                .thenAnswer(invocation -> {
                    TroubleshootingManualPlaybookReplayEntity inserted =
                            invocation.getArgument(0);
                    inserted.setId(1L);
                    row.set(inserted);
                    return 1;
                });
        MybatisManualPlaybookReplayAttestationStore store =
                new MybatisManualPlaybookReplayAttestationStore(
                        mapper, new ObjectMapper().findAndRegisterModules());

        ManualPlaybookReplayAttestationStore.Stored first =
                store.saveOrGet(7L, attestation());
        ManualPlaybookReplayAttestationStore.Stored retry =
                store.saveOrGet(7L, attestation());

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.attestation()).isEqualTo(attestation());
        assertThat(row.get().getWorkspaceId()).isEqualTo(7L);
        assertThat(row.get().getSourceRecordId()).isEqualTo("manual-topology-v1");
        assertThat(row.get().getStatus()).isEqualTo("PASSED");
        assertThat(row.get().getResultJson())
                .contains("positivePassed")
                .doesNotContain("failed_probe_count", "target_url", "query");
        verify(mapper, times(1)).insert(
                any(TroubleshootingManualPlaybookReplayEntity.class));
    }

    @Test
    void recoversTheWinnerOutsideAnAbortedUniqueInsertTransaction() throws Exception {
        TroubleshootingManualPlaybookReplayMapper mapper =
                mock(TroubleshootingManualPlaybookReplayMapper.class);
        TroubleshootingManualPlaybookReplayEntity winner = entity(attestation());
        when(mapper.selectOne(any())).thenReturn(null, winner);
        when(mapper.insert(any(TroubleshootingManualPlaybookReplayEntity.class)))
                .thenThrow(new DataIntegrityViolationException("simulated race"));
        MybatisManualPlaybookReplayAttestationStore store =
                new MybatisManualPlaybookReplayAttestationStore(
                        mapper, new ObjectMapper().findAndRegisterModules());

        ManualPlaybookReplayAttestationStore.Stored result =
                store.saveOrGet(7L, attestation());

        assertThat(result.created()).isFalse();
        assertThat(result.attestation()).isEqualTo(attestation());
        assertThat(MybatisManualPlaybookReplayAttestationStore.class
                .getMethod(
                        "saveOrGet", long.class,
                        ManualPlaybookReplayAttestation.class)
                .getAnnotation(Transactional.class))
                .extracting(Transactional::propagation)
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void exactLookupUsesWorkspaceSourceAndBothFingerprints() {
        TroubleshootingManualPlaybookReplayMapper mapper =
                mock(TroubleshootingManualPlaybookReplayMapper.class);
        when(mapper.selectOne(any())).thenReturn(entity(attestation()));
        MybatisManualPlaybookReplayAttestationStore store =
                new MybatisManualPlaybookReplayAttestationStore(
                        mapper, new ObjectMapper().findAndRegisterModules());

        assertThat(store.find(
                7L,
                "manual-topology-v1",
                "a".repeat(64),
                "b".repeat(64))).contains(attestation());

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper>
                query = ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(mapper).selectOne(query.capture());
        assertThat(query.getValue().getCustomSqlSegment())
                .contains("workspace_id")
                .contains("source_record_id")
                .contains("candidate_fingerprint")
                .contains("suite_fingerprint")
                .contains("deleted");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains(7L, "manual-topology-v1", "a".repeat(64), "b".repeat(64), 0);
    }

    private TroubleshootingManualPlaybookReplayEntity entity(
            ManualPlaybookReplayAttestation attestation) {
        try {
            TroubleshootingManualPlaybookReplayEntity entity =
                    new TroubleshootingManualPlaybookReplayEntity();
            entity.setId(1L);
            entity.setWorkspaceId(7L);
            entity.setAttestationId(attestation.attestationId());
            entity.setSourceRecordId(attestation.sourceRecordId());
            entity.setSelectorKey(attestation.selectorKey());
            entity.setCandidateFingerprint(attestation.candidateFingerprint());
            entity.setSuiteId(attestation.suiteId());
            entity.setSuiteVersion(attestation.suiteVersion());
            entity.setSuiteFingerprint(attestation.suiteFingerprint());
            entity.setStatus(attestation.status().name());
            entity.setResultJson(new ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(attestation));
            entity.setExecutedBy(attestation.executedBy());
            entity.setDeleted(0);
            return entity;
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private ManualPlaybookReplayAttestation attestation() {
        return new ManualPlaybookReplayAttestation(
                "attestation-1",
                "manual-topology-v1",
                "csdp:scenario:deployment_topology_probe",
                "a".repeat(64),
                "deployment-topology-probe/v1",
                1,
                "b".repeat(64),
                ManualPlaybookReplayAttestation.Status.PASSED,
                1, 1, 2, 2, List.of(), true,
                "reviewer-a", Instant.parse("2026-07-31T03:00:00Z"));
    }
}
