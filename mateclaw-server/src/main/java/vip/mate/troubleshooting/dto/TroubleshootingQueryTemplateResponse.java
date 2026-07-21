package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.TroubleshootingQueryTemplateEntity;

import java.time.LocalDateTime;

public record TroubleshootingQueryTemplateResponse(
        Long id,
        Long workspaceId,
        String provider,
        String evidenceType,
        String templateKey,
        String name,
        String description,
        String payloadTemplate,
        String dqlTemplate,
        String matchJson,
        boolean enabled,
        boolean defaultTemplate,
        int priority,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public static TroubleshootingQueryTemplateResponse from(TroubleshootingQueryTemplateEntity entity) {
        return new TroubleshootingQueryTemplateResponse(
                entity.getId(),
                entity.getWorkspaceId(),
                entity.getProvider(),
                entity.getEvidenceType(),
                entity.getTemplateKey(),
                entity.getName(),
                entity.getDescription(),
                entity.getPayloadTemplate(),
                entity.getDqlTemplate(),
                entity.getMatchJson(),
                intFlag(entity.getEnabled(), true),
                intFlag(entity.getDefaultTemplate(), false),
                entity.getPriority() == null ? 0 : entity.getPriority(),
                entity.getCreateTime(),
                entity.getUpdateTime()
        );
    }

    private static boolean intFlag(Integer value, boolean defaultValue) {
        return value == null ? defaultValue : value != 0;
    }
}
