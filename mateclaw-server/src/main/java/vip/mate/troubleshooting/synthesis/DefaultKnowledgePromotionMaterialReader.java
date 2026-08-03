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
        // 成色由目录判定，**能不能批准**不在这里判。此前这里读的是只在候选与随包
        // 示例逐字节相同时才有值的 evidenceGrade，空值当成拒绝——于是「资格」说可以、
        // 「促成」说不行，评审面板对作者撒了谎，而 409 里没有半个字提到真正的原因。
        return Optional.of(new KnowledgePromotionMaterial(
                origin,
                candidate.sopId(),
                candidate.routingKey(),
                replayCatalog.promotionGrade(candidate.routingKey(), candidate),
                candidate));
    }
}
