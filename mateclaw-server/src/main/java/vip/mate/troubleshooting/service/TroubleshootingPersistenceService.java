package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.KnowledgePublicationStatus;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.model.ScenarioSelector;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeOutboxMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Transaction boundary for diagnosis aggregates and knowledge-candidate outbox rows.
 * The rule engine never depends on this service and remains database-free.
 */
@Service
public class TroubleshootingPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(TroubleshootingPersistenceService.class);

    private final TroubleshootingDiagnosisMapper diagnosisMapper;
    private final TroubleshootingKnowledgeOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final TroubleshootingPilotPlanService pilotPlans;

    public TroubleshootingPersistenceService(
            TroubleshootingDiagnosisMapper diagnosisMapper,
            TroubleshootingKnowledgeOutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            TroubleshootingPilotPlanService pilotPlans) {
        this.diagnosisMapper = diagnosisMapper;
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.pilotPlans = pilotPlans;
    }

    @Transactional
    public StoredDiagnosis createOrGet(
            long workspaceId,
            Diagnosis diagnosis,
            Instant receivedAt) {
        validateCreate(workspaceId, diagnosis);
        return persistCreateOrGet(
                workspaceId,
                diagnosis,
                IncidentDeduplicationKey.create(
                        diagnosis.incident(), diagnosis.rehearsal(), receivedAt),
                null);
    }

    /**
     * Creates exactly one Diagnosis for an IntakeSession.
     *
     * <p>The source Intake ID is a stronger idempotency boundary than the
     * generic five-minute incident bucket. Two independently reported channel
     * sessions must never collapse merely because their route and event time
     * happen to match.</p>
     */
    @Transactional
    public StoredDiagnosis createOrGetForIntake(
            long workspaceId,
            Diagnosis diagnosis,
            String intakeSessionId) {
        validateCreate(workspaceId, diagnosis);
        if (intakeSessionId == null || intakeSessionId.isBlank()) {
            throw new IllegalArgumentException("intakeSessionId must not be blank");
        }
        return persistCreateOrGet(
                workspaceId, diagnosis, Optional.empty(), intakeSessionId.trim());
    }

    /**
     * Creates or reuses only a Diagnosis from the same explicit scenario path.
     * The scenario discriminator prevents a generic symptom report, or another
     * scenario, from becoming this tool run's evidence owner.
     */
    @Transactional
    public StoredDiagnosis createOrGetForScenario(
            long workspaceId,
            Diagnosis diagnosis,
            String scenarioKey,
            Instant receivedAt) {
        validateCreate(workspaceId, diagnosis);
        validateScenarioIdentity(diagnosis, scenarioKey);
        return persistCreateOrGet(
                workspaceId,
                diagnosis,
                IncidentDeduplicationKey.createForScenario(
                        diagnosis.incident(), scenarioKey, diagnosis.rehearsal(), receivedAt),
                null);
    }

    private StoredDiagnosis persistCreateOrGet(
            long workspaceId,
            Diagnosis diagnosis,
            Optional<String> dedupKey,
            String intakeSessionId) {
        if (intakeSessionId != null) {
            TroubleshootingDiagnosisEntity existing = findEntityByIntakeSessionId(
                    workspaceId, intakeSessionId);
            if (existing != null) {
                return stored(existing, false);
            }
        }
        if (dedupKey.isPresent()) {
            TroubleshootingDiagnosisEntity existing = findByDedupKey(workspaceId, dedupKey.get());
            if (existing != null) {
                return stored(existing, false);
            }
        }

        TroubleshootingDiagnosisEntity entity = entity(
                workspaceId, diagnosis, dedupKey.orElse(null), intakeSessionId);
        try {
            diagnosisMapper.insert(entity);
            return new StoredDiagnosis(diagnosis, 0, true);
        } catch (DuplicateKeyException collision) {
            if (intakeSessionId != null) {
                TroubleshootingDiagnosisEntity existing = findEntityByIntakeSessionId(
                        workspaceId, intakeSessionId);
                if (existing != null) {
                    return stored(existing, false);
                }
                throw collision;
            }
            if (dedupKey.isEmpty()) {
                throw collision;
            }
            TroubleshootingDiagnosisEntity existing = findByDedupKey(workspaceId, dedupKey.get());
            if (existing == null) {
                throw collision;
            }
            return stored(existing, false);
        }
    }

    private void validateCreate(long workspaceId, Diagnosis diagnosis) {
        validateWorkspace(workspaceId);
        if (diagnosis == null) {
            throw new IllegalArgumentException("diagnosis must not be null");
        }
    }

    private void validateScenarioIdentity(Diagnosis diagnosis, String scenarioKey) {
        if (diagnosis.investigationMode() != InvestigationMode.SCENARIO_PLAYBOOK) {
            throw new IllegalArgumentException(
                    "scenario persistence requires a SCENARIO_PLAYBOOK diagnosis");
        }
        String expectedSelector = new ScenarioSelector(
                diagnosis.incident().system(), scenarioKey).routingKey();
        if (!expectedSelector.equals(diagnosis.sopKey())) {
            throw new IllegalArgumentException(
                    "scenarioKey does not match the diagnosis selector: " + expectedSelector);
        }
    }

    public StoredDiagnosis get(long workspaceId, String diagnosisId) {
        validateWorkspace(workspaceId);
        TroubleshootingDiagnosisEntity entity = diagnosisMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDiagnosisId, diagnosisId)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0));
        if (entity == null) {
            throw new MateClawException(
                    "err.troubleshooting.diagnosis_not_found",
                    404,
                    "troubleshooting diagnosis not found: " + diagnosisId);
        }
        return stored(entity, false);
    }

    /**
     * Finds the Diagnosis already owned by an IntakeSession without starting
     * evidence collection or invoking the miss-path Agent again.
     */
    public Optional<StoredDiagnosis> findByIntakeSessionId(
            long workspaceId,
            String intakeSessionId) {
        validateWorkspace(workspaceId);
        if (intakeSessionId == null || intakeSessionId.isBlank()) {
            throw new IllegalArgumentException("intakeSessionId must not be blank");
        }
        return Optional.ofNullable(findEntityByIntakeSessionId(
                        workspaceId, intakeSessionId.trim()))
                .map(entity -> stored(entity, false));
    }

    /** Finds a generic five-minute incident bucket before any external work starts. */
    public Optional<StoredDiagnosis> findByIncident(
            long workspaceId,
            vip.mate.troubleshooting.model.IncidentContext incident,
            boolean rehearsal,
            Instant receivedAt) {
        validateWorkspace(workspaceId);
        return IncidentDeduplicationKey.create(incident, rehearsal, receivedAt)
                .map(key -> findByDedupKey(workspaceId, key))
                .map(entity -> stored(entity, false));
    }

    @Transactional
    public StoredDiagnosis update(long workspaceId, Diagnosis diagnosis, int expectedVersion) {
        updateAggregate(workspaceId, diagnosis, expectedVersion);
        return new StoredDiagnosis(diagnosis, expectedVersion + 1, false);
    }

    /**
     * Lists queue rows for one workspace, newest first.
     *
     * <p>Reads indexed columns only — the stored aggregate is never parsed here,
     * so rendering a queue costs the same whether a diagnosis carries three
     * pieces of evidence or thirty. {@code status} and {@code system} narrow the
     * list when supplied; a blank value means "no filter" rather than "match
     * blank", because that is what an empty console filter box means.</p>
     */
    public java.util.List<DiagnosisSummary> list(
            long workspaceId,
            String status,
            String system,
            InvestigationMode investigationMode,
            int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<TroubleshootingDiagnosisEntity> query =
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingDiagnosisEntity::getId)
                        .last("LIMIT " + capped);
        if (status != null && !status.isBlank()) {
            query.eq(TroubleshootingDiagnosisEntity::getStatus, status.trim());
        }
        if (system != null && !system.isBlank()) {
            query.eq(TroubleshootingDiagnosisEntity::getSystemName, system.trim());
        }
        if (investigationMode != null) {
            query.eq(
                    TroubleshootingDiagnosisEntity::getInvestigationMode,
                    investigationMode.name());
        }
        java.util.List<DiagnosisSummary> rows = new java.util.ArrayList<>();
        for (TroubleshootingDiagnosisEntity entity : diagnosisMapper.selectList(query)) {
            try {
                rows.add(DiagnosisSummary.from(entity));
            } catch (RuntimeException failure) {
                // One corrupt legacy/indexed row must not blank the duty queue.
                log.warn("Skipping diagnosis {} in queue: {}",
                        entity.getDiagnosisId(), failure.toString());
            }
        }
        return rows;
    }

    @Transactional
    public StoredDiagnosis updateAndEnqueue(
            long workspaceId,
            Diagnosis diagnosis,
            int expectedVersion,
            KnowledgeCandidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate must not be null");
        }
        if (!candidate.sourceDiagnosisId().equals(diagnosis.diagnosisId())) {
            throw new IllegalArgumentException("candidate must belong to the diagnosis being persisted");
        }
        if (diagnosis.knowledgeCandidates().stream()
                .noneMatch(item -> item.candidateId().equals(candidate.candidateId()))) {
            throw new IllegalArgumentException("candidate must already be part of the diagnosis aggregate");
        }
        updateAggregate(workspaceId, diagnosis, expectedVersion);
        enqueueIfAbsent(workspaceId, candidate);
        return new StoredDiagnosis(diagnosis, expectedVersion + 1, false);
    }

    /**
     * Lists knowledge candidates awaiting review, newest first.
     *
     * <p>Read-only for now, and deliberately so. The outbox column these rows
     * carry is a <em>publication</em> state (has the candidate been handed to a
     * sink), not a <em>review</em> state (has an expert judged it worth folding
     * into a SOP) — the two happen to look alike and conflating them would let a
     * delivery retry masquerade as an approval. Giving reviewers sight of the
     * queue is useful today; the review workflow itself needs its own state and
     * is tracked as follow-up work.</p>
     */
    public java.util.List<KnowledgeCandidate> listKnowledgeCandidates(long workspaceId, int limit) {
        validateWorkspace(workspaceId);
        int capped = Math.min(Math.max(limit, 1), 200);
        java.util.List<TroubleshootingKnowledgeOutboxEntity> rows = outboxMapper.selectList(
                new LambdaQueryWrapper<TroubleshootingKnowledgeOutboxEntity>()
                        .eq(TroubleshootingKnowledgeOutboxEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingKnowledgeOutboxEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingKnowledgeOutboxEntity::getId)
                        .last("LIMIT " + capped));
        java.util.List<KnowledgeCandidate> candidates = new java.util.ArrayList<>(rows.size());
        for (TroubleshootingKnowledgeOutboxEntity row : rows) {
            try {
                candidates.add(objectMapper.readValue(
                        row.getPayloadJson(), KnowledgeCandidate.class));
            } catch (JsonProcessingException error) {
                // One unreadable row must not hide the rest of the queue; the
                // poller reports its own failures separately.
                continue;
            }
        }
        return candidates;
    }

    /** Finds one outcome-backed candidate without scanning or crossing workspaces. */
    public KnowledgeCandidate findKnowledgeCandidate(long workspaceId, String candidateId) {
        validateWorkspace(workspaceId);
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("candidateId must not be blank");
        }
        TroubleshootingKnowledgeOutboxEntity row = outboxMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingKnowledgeOutboxEntity>()
                        .eq(TroubleshootingKnowledgeOutboxEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingKnowledgeOutboxEntity::getCandidateId,
                                candidateId.trim())
                        .eq(TroubleshootingKnowledgeOutboxEntity::getDeleted, 0));
        if (row == null) {
            return null;
        }
        try {
            KnowledgeCandidate candidate = objectMapper.readValue(
                    row.getPayloadJson(), KnowledgeCandidate.class);
            if (!candidate.candidateId().equals(candidateId.trim())) {
                throw new MateClawException(
                        "err.troubleshooting.knowledge_candidate_identity",
                        500,
                        "knowledge candidate payload does not match its indexed identity");
            }
            return candidate;
        } catch (JsonProcessingException error) {
            throw serializationError("deserialize knowledge candidate", error);
        }
    }

    private void updateAggregate(long workspaceId, Diagnosis diagnosis, int expectedVersion) {
        validateWorkspace(workspaceId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        LocalDateTime now = utcNow();
        int changed = diagnosisMapper.update(
                null,
                new LambdaUpdateWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDiagnosisId, diagnosis.diagnosisId())
                        .eq(TroubleshootingDiagnosisEntity::getVersion, expectedVersion)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0)
                        .set(TroubleshootingDiagnosisEntity::getStatus, diagnosis.status().name())
                        .set(TroubleshootingDiagnosisEntity::getContractVersion, diagnosis.contractVersion())
                        .set(TroubleshootingDiagnosisEntity::getAggregateJson, json(diagnosis))
                        .set(
                                TroubleshootingDiagnosisEntity::getInvestigationMode,
                                persistedInvestigationMode(diagnosis))
                        .set(
                                TroubleshootingDiagnosisEntity::getRouteAuthority,
                                persistedRouteAuthority(diagnosis))
                        .set(TroubleshootingDiagnosisEntity::getVersion, expectedVersion + 1)
                        .set(TroubleshootingDiagnosisEntity::getUpdateTime, now));
        if (changed != 1) {
            throw new MateClawException(
                    "err.troubleshooting.optimistic_lock_conflict",
                    409,
                    "diagnosis changed concurrently; reload before applying the transition");
        }
        if (diagnosis.status() == DiagnosisStatus.CLOSED) {
            diagnosisMapper.scheduleClosureNotification(
                    workspaceId, diagnosis.diagnosisId(), now);
        }
    }

    private void enqueueIfAbsent(long workspaceId, KnowledgeCandidate candidate) {
        String publicationId = "publication-" + candidate.candidateId();
        TroubleshootingKnowledgeOutboxEntity existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingKnowledgeOutboxEntity>()
                        .eq(TroubleshootingKnowledgeOutboxEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingKnowledgeOutboxEntity::getPublicationId, publicationId)
                        .eq(TroubleshootingKnowledgeOutboxEntity::getDeleted, 0));
        if (existing != null) {
            return;
        }
        LocalDateTime now = utcNow();
        TroubleshootingKnowledgeOutboxEntity outbox = new TroubleshootingKnowledgeOutboxEntity();
        outbox.setWorkspaceId(workspaceId);
        outbox.setPublicationId(publicationId);
        outbox.setDiagnosisId(candidate.sourceDiagnosisId());
        outbox.setCandidateId(candidate.candidateId());
        outbox.setEventType("KNOWLEDGE_CANDIDATE_CREATED");
        outbox.setContractVersion(candidate.contractVersion());
        outbox.setPayloadJson(json(candidate));
        outbox.setStatus(KnowledgePublicationStatus.PENDING);
        outbox.setAttempts(0);
        outbox.setDeleted(0);
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        outboxMapper.insert(outbox);
    }

    private TroubleshootingDiagnosisEntity findByDedupKey(long workspaceId, String dedupKey) {
        return diagnosisMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingDiagnosisEntity::getDedupKey, dedupKey)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0));
    }

    private TroubleshootingDiagnosisEntity findEntityByIntakeSessionId(
            long workspaceId,
            String intakeSessionId) {
        return diagnosisMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingDiagnosisEntity>()
                        .eq(TroubleshootingDiagnosisEntity::getWorkspaceId, workspaceId)
                        .eq(
                                TroubleshootingDiagnosisEntity::getSourceIntakeSessionId,
                                intakeSessionId)
                        .eq(TroubleshootingDiagnosisEntity::getDeleted, 0));
    }

    private TroubleshootingDiagnosisEntity entity(
            long workspaceId,
            Diagnosis diagnosis,
            String dedupKey,
            String intakeSessionId) {
        LocalDateTime now = utcNow();
        TroubleshootingDiagnosisEntity entity = new TroubleshootingDiagnosisEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setDiagnosisId(diagnosis.diagnosisId());
        entity.setCaseId(diagnosis.caseId());
        entity.setRunId(diagnosis.runId());
        entity.setSystem(diagnosis.incident().system());
        entity.setErrorCode(diagnosis.incident().errorCode());
        entity.setService(diagnosis.incident().service());
        entity.setDedupKey(dedupKey);
        entity.setSourceIntakeSessionId(intakeSessionId);
        entity.setRehearsal(diagnosis.rehearsal());
        entity.setStatus(diagnosis.status().name());
        entity.setContractVersion(diagnosis.contractVersion());
        entity.setAggregateJson(json(diagnosis));
        entity.setInvestigationMode(persistedInvestigationMode(diagnosis));
        entity.setRouteAuthority(persistedRouteAuthority(diagnosis));
        entity.setPilotPlanVersion(pilotPlans.enrollmentVersion(
                workspaceId,
                diagnosis.incident().system(),
                diagnosis.incident().service(),
                diagnosis.rehearsal()));
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        return entity;
    }

    private StoredDiagnosis stored(TroubleshootingDiagnosisEntity entity, boolean created) {
        try {
            Diagnosis diagnosis = objectMapper.readValue(entity.getAggregateJson(), Diagnosis.class);
            return new StoredDiagnosis(diagnosis, entity.getVersion(), created);
        } catch (JsonProcessingException error) {
            throw serializationError("deserialize diagnosis", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw serializationError("serialize troubleshooting aggregate", error);
        }
    }

    private MateClawException serializationError(String operation, Exception error) {
        return new MateClawException(
                "err.troubleshooting.contract_serialization",
                500,
                "failed to " + operation + ": " + error.getMessage());
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
    }

    private String persistedInvestigationMode(Diagnosis diagnosis) {
        if (diagnosis.routeSemanticsProvenance() != RouteSemanticsProvenance.PERSISTED) {
            return null;
        }
        InvestigationMode investigationMode = diagnosis.investigationMode();
        return investigationMode == null ? null : investigationMode.name();
    }

    private String persistedRouteAuthority(Diagnosis diagnosis) {
        if (diagnosis.routeSemanticsProvenance() != RouteSemanticsProvenance.PERSISTED) {
            return null;
        }
        RouteAuthority routeAuthority = diagnosis.routeAuthority();
        return routeAuthority == null ? null : routeAuthority.name();
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
