package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookVersionMapper;

import java.util.List;
import java.util.Optional;

/**
 * Fail-closed upgrade for versions written before knowledge evidence grading.
 *
 * <p>Public source IDs and selectors are not authority. V190 therefore leaves
 * every historical row UNVERIFIED. This runner reconstructs the original
 * candidate shape and delegates to the catalog's canonical SHA-256 comparison;
 * only an exact server-owned candidate can receive a stronger grade.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HistoricalKnowledgeEvidenceGradeReconciler
        implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(
            HistoricalKnowledgeEvidenceGradeReconciler.class);
    static final int PAGE_SIZE = 250;

    private final TroubleshootingPlaybookVersionMapper versions;
    private final ObjectMapper objectMapper;
    private final ManualPlaybookReplaySuiteCatalog replayCatalog;

    public HistoricalKnowledgeEvidenceGradeReconciler(
            TroubleshootingPlaybookVersionMapper versions,
            ObjectMapper objectMapper,
            ManualPlaybookReplaySuiteCatalog replayCatalog) {
        this.versions = versions;
        this.objectMapper = objectMapper;
        this.replayCatalog = replayCatalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        int upgraded = 0;
        long lastSeenId = 0L;
        while (true) {
            List<TroubleshootingPlaybookVersionEntity> legacy;
            try {
                legacy = versions.listUnverifiedKnowledgeEvidenceGradesAfter(
                        lastSeenId, PAGE_SIZE);
            } catch (RuntimeException failure) {
                log.warn("[knowledge-evidence] historical reconciliation stopped;"
                        + " remaining legacy grades stay UNVERIFIED");
                return;
            }
            if (legacy.isEmpty()) {
                break;
            }

            long pageLastSeenId = lastSeenId;
            for (TroubleshootingPlaybookVersionEntity version : legacy) {
                if (version == null || version.getId() == null) {
                    continue;
                }
                pageLastSeenId = Math.max(pageLastSeenId, version.getId());
                Optional<KnowledgeEvidenceGrade> exact = exactGrade(version);
                if (exact.isEmpty()) {
                    continue;
                }
                try {
                    upgraded += versions.backfillKnowledgeEvidenceGrade(
                            version.getId(), exact.get().name());
                } catch (RuntimeException failure) {
                    log.warn("[knowledge-evidence] one exact historical grade could not"
                            + " be persisted; it remains UNVERIFIED");
                }
            }
            if (pageLastSeenId <= lastSeenId) {
                log.warn("[knowledge-evidence] historical reconciliation stopped on"
                        + " a non-advancing page; remaining grades stay UNVERIFIED");
                return;
            }
            lastSeenId = pageLastSeenId;
        }
        if (upgraded > 0) {
            log.info("[knowledge-evidence] reconciled {} exact historical"
                    + " Playbook version(s)", upgraded);
        }
    }

    Optional<KnowledgeEvidenceGrade> exactGrade(
            TroubleshootingPlaybookVersionEntity version) {
        if (version == null
                || version.getId() == null
                || version.getSourceRecordId() == null
                || version.getSourceRecordId().isBlank()
                || version.getSelectorKey() == null
                || version.getSelectorKey().isBlank()
                || version.getAggregateJson() == null
                || version.getAggregateJson().isBlank()) {
            return Optional.empty();
        }
        try {
            SopEntry stored = objectMapper.readValue(
                    version.getAggregateJson(), SopEntry.class);
            SopEntry candidate = new SopEntry(
                    version.getSourceRecordId(),
                    stored.contractVersion(),
                    stored.system(),
                    stored.errorCode(),
                    stored.service(),
                    stored.title(),
                    stored.cause(),
                    stored.category(),
                    stored.ownerTeam(),
                    "candidate",
                    false,
                    stored.evidenceRequests(),
                    stored.anomalyCriteria(),
                    stored.diagnosisRules(),
                    stored.actions(),
                    stored.symptomTriggers());
            return replayCatalog.evidenceGrade(
                    version.getSelectorKey(), candidate);
        } catch (JsonProcessingException | RuntimeException invalidLegacyRow) {
            return Optional.empty();
        }
    }
}
