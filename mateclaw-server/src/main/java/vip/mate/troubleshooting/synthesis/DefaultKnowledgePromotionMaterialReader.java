package vip.mate.troubleshooting.synthesis;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.util.Optional;

/**
 * Builds promotion material without accepting a browser-authored Playbook.
 *
 * <p>The current executable {@link SopEntry} contract represents deterministic
 * error-code Playbooks. Manual candidates already own that full contract. The
 * evidence-derived and outcome-backed source contracts remain ineligible until
 * their real replay/owner/outcome proof also projects an executable approved
 * artifact; returning empty keeps that future boundary fail closed.</p>
 */
@Component
public class DefaultKnowledgePromotionMaterialReader
        implements KnowledgePromotionMaterialReader {

    private final TroubleshootingSopPersistenceService manualCandidates;
    private final ManualPlaybookReplaySuiteCatalog replayCatalog;

    public DefaultKnowledgePromotionMaterialReader(
            TroubleshootingSopPersistenceService manualCandidates,
            ManualPlaybookReplaySuiteCatalog replayCatalog) {
        this.manualCandidates = manualCandidates;
        this.replayCatalog = replayCatalog;
    }

    @Override
    public Optional<KnowledgePromotionMaterial> find(
            long workspaceId,
            KnowledgeOrigin origin,
            String sourceRecordId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (origin == null || sourceRecordId == null || sourceRecordId.isBlank()) {
            throw new IllegalArgumentException("origin and sourceRecordId are required");
        }
        if (origin != KnowledgeOrigin.MANUAL) {
            return Optional.empty();
        }
        SopEntry candidate = manualCandidates.findBySopId(
                workspaceId, sourceRecordId.trim());
        if (candidate == null
                || !"candidate".equals(candidate.status())
                || candidate.verified()) {
            return Optional.empty();
        }
        var evidenceGrade = replayCatalog.evidenceGrade(
                candidate.routingKey(), candidate);
        if (evidenceGrade.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KnowledgePromotionMaterial(
                origin,
                candidate.sopId(),
                candidate.routingKey(),
                evidenceGrade.get(),
                candidate));
    }
}
