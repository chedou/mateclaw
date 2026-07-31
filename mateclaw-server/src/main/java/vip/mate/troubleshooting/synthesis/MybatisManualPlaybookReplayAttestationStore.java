package vip.mate.troubleshooting.synthesis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingManualPlaybookReplayEntity;
import vip.mate.troubleshooting.repository.TroubleshootingManualPlaybookReplayMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/** MyBatis store with unique-race recovery for immutable replay attestations. */
@Component
public class MybatisManualPlaybookReplayAttestationStore
        implements ManualPlaybookReplayAttestationStore {

    private final TroubleshootingManualPlaybookReplayMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisManualPlaybookReplayAttestationStore(
            TroubleshootingManualPlaybookReplayMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ManualPlaybookReplayAttestation> find(
            long workspaceId,
            String sourceRecordId,
            String candidateFingerprint,
            String suiteFingerprint) {
        validateWorkspace(workspaceId);
        TroubleshootingManualPlaybookReplayEntity entity = mapper.selectOne(
                exactQuery(
                        workspaceId,
                        required(sourceRecordId, "sourceRecordId"),
                        required(candidateFingerprint, "candidateFingerprint"),
                        required(suiteFingerprint, "suiteFingerprint")));
        return entity == null ? Optional.empty() : Optional.of(read(entity));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Stored saveOrGet(
            long workspaceId,
            ManualPlaybookReplayAttestation attestation) {
        validateWorkspace(workspaceId);
        if (attestation == null) {
            throw new IllegalArgumentException("attestation is required");
        }
        Optional<ManualPlaybookReplayAttestation> existing = find(
                workspaceId,
                attestation.sourceRecordId(),
                attestation.candidateFingerprint(),
                attestation.suiteFingerprint());
        if (existing.isPresent()) {
            return new Stored(existing.get(), false);
        }
        try {
            mapper.insert(entity(workspaceId, attestation));
            return new Stored(attestation, true);
        } catch (DataIntegrityViolationException raced) {
            existing = find(
                    workspaceId,
                    attestation.sourceRecordId(),
                    attestation.candidateFingerprint(),
                    attestation.suiteFingerprint());
            if (existing.isEmpty()) {
                throw raced;
            }
            return new Stored(existing.get(), false);
        }
    }

    private LambdaQueryWrapper<TroubleshootingManualPlaybookReplayEntity> exactQuery(
            long workspaceId,
            String sourceRecordId,
            String candidateFingerprint,
            String suiteFingerprint) {
        return new LambdaQueryWrapper<TroubleshootingManualPlaybookReplayEntity>()
                .eq(TroubleshootingManualPlaybookReplayEntity::getWorkspaceId, workspaceId)
                .eq(TroubleshootingManualPlaybookReplayEntity::getSourceRecordId,
                        sourceRecordId)
                .eq(TroubleshootingManualPlaybookReplayEntity::getCandidateFingerprint,
                        candidateFingerprint)
                .eq(TroubleshootingManualPlaybookReplayEntity::getSuiteFingerprint,
                        suiteFingerprint)
                .eq(TroubleshootingManualPlaybookReplayEntity::getDeleted, 0);
    }

    private TroubleshootingManualPlaybookReplayEntity entity(
            long workspaceId,
            ManualPlaybookReplayAttestation attestation) {
        LocalDateTime executedAt = LocalDateTime.ofInstant(
                attestation.executedAt(), ZoneOffset.UTC);
        TroubleshootingManualPlaybookReplayEntity entity =
                new TroubleshootingManualPlaybookReplayEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setAttestationId(attestation.attestationId());
        entity.setSourceRecordId(attestation.sourceRecordId());
        entity.setSelectorKey(attestation.selectorKey());
        entity.setCandidateFingerprint(attestation.candidateFingerprint());
        entity.setSuiteId(attestation.suiteId());
        entity.setSuiteVersion(attestation.suiteVersion());
        entity.setSuiteFingerprint(attestation.suiteFingerprint());
        entity.setStatus(attestation.status().name());
        entity.setResultJson(write(attestation));
        entity.setExecutedBy(attestation.executedBy());
        entity.setExecutedAt(executedAt);
        entity.setDeleted(0);
        entity.setCreateTime(executedAt);
        entity.setUpdateTime(executedAt);
        return entity;
    }

    private ManualPlaybookReplayAttestation read(
            TroubleshootingManualPlaybookReplayEntity entity) {
        try {
            ManualPlaybookReplayAttestation attestation = objectMapper.readValue(
                    entity.getResultJson(), ManualPlaybookReplayAttestation.class);
            if (!Objects.equals(entity.getAttestationId(), attestation.attestationId())
                    || !Objects.equals(
                            entity.getSourceRecordId(), attestation.sourceRecordId())
                    || !Objects.equals(entity.getSelectorKey(), attestation.selectorKey())
                    || !Objects.equals(
                            entity.getCandidateFingerprint(),
                            attestation.candidateFingerprint())
                    || !Objects.equals(entity.getSuiteId(), attestation.suiteId())
                    || !Objects.equals(entity.getSuiteVersion(), attestation.suiteVersion())
                    || !Objects.equals(
                            entity.getSuiteFingerprint(), attestation.suiteFingerprint())
                    || !Objects.equals(entity.getStatus(), attestation.status().name())
                    || !Objects.equals(entity.getExecutedBy(), attestation.executedBy())) {
                throw invalidStoredAttestation();
            }
            return attestation;
        } catch (JsonProcessingException failure) {
            throw invalidStoredAttestation();
        }
    }

    private String write(ManualPlaybookReplayAttestation attestation) {
        try {
            return objectMapper.writeValueAsString(attestation);
        } catch (JsonProcessingException failure) {
            throw new MateClawException(
                    "err.troubleshooting.manual_replay_persistence_failed",
                    500,
                    "manual Playbook replay attestation cannot be serialized");
        }
    }

    private MateClawException invalidStoredAttestation() {
        return new MateClawException(
                "err.troubleshooting.manual_replay_invalid_stored",
                500,
                "stored manual Playbook replay attestation is invalid");
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
