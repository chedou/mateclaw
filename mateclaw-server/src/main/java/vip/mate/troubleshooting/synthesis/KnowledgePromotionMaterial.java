package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.SopEntry;

/** Server-owned routeable artifact; the browser never supplies promotion content. */
public record KnowledgePromotionMaterial(
        KnowledgeOrigin origin,
        String sourceRecordId,
        String selectorKey,
        SopEntry playbook) {

    public KnowledgePromotionMaterial {
        if (origin == null
                || sourceRecordId == null || sourceRecordId.isBlank()
                || selectorKey == null || selectorKey.isBlank()
                || playbook == null) {
            throw new IllegalArgumentException("knowledge promotion material is incomplete");
        }
        sourceRecordId = sourceRecordId.trim();
        selectorKey = selectorKey.trim();
    }
}
