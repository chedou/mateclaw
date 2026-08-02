package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;

/** Server-owned routeable artifact; the browser never supplies promotion content. */
public record KnowledgePromotionMaterial(
        KnowledgeOrigin origin,
        String sourceRecordId,
        String selectorKey,
        KnowledgeEvidenceGrade evidenceGrade,
        SopEntry playbook) {

    /** Compatibility shape; unknown callers never gain recorded authority. */
    public KnowledgePromotionMaterial(
            KnowledgeOrigin origin,
            String sourceRecordId,
            String selectorKey,
            SopEntry playbook) {
        this(origin, sourceRecordId, selectorKey,
                KnowledgeEvidenceGrade.UNVERIFIED, playbook);
    }

    public KnowledgePromotionMaterial {
        if (origin == null
                || sourceRecordId == null || sourceRecordId.isBlank()
                || selectorKey == null || selectorKey.isBlank()
                || evidenceGrade == null
                || playbook == null) {
            throw new IllegalArgumentException("knowledge promotion material is incomplete");
        }
        sourceRecordId = sourceRecordId.trim();
        selectorKey = selectorKey.trim();
    }
}
