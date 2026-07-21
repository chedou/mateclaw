package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.dto.TroubleshootingConnectorConfigRequest;
import vip.mate.troubleshooting.dto.TroubleshootingConnectorConfigResponse;
import vip.mate.troubleshooting.evidence.TroubleshootingEvidenceProperties;
import vip.mate.troubleshooting.model.TroubleshootingConnectorConfigEntity;
import vip.mate.troubleshooting.repository.TroubleshootingConnectorConfigMapper;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TroubleshootingConnectorConfigService {

    private static final String GUANCE = "guance";

    private final TroubleshootingConnectorConfigMapper configMapper;
    private final TroubleshootingEvidenceProperties evidenceProperties;

    public TroubleshootingConnectorConfigResponse getGuanceConfig(long workspaceId) {
        Optional<TroubleshootingConnectorConfigEntity> stored = find(workspaceId, GUANCE);
        if (stored.isPresent()) {
            TroubleshootingConnectorConfigEntity entity = stored.get();
            return TroubleshootingConnectorConfigResponse.fromEntity(
                    entity,
                    hasText(entity.getToken()) || hasText(evidenceProperties.getGuance().getToken()),
                    hasText(entity.getToken()) ? "database" : hasText(evidenceProperties.getGuance().getToken()) ? "environment" : "none"
            );
        }
        TroubleshootingEvidenceProperties.Guance cfg = evidenceProperties.getGuance();
        return new TroubleshootingConnectorConfigResponse(
                GUANCE,
                false,
                cfg.isEnabled(),
                cfg.getBaseUrl(),
                cfg.getSyntheticsPath(),
                cfg.getMetricsPath(),
                cfg.getTokenHeader(),
                cfg.getTokenPrefix(),
                hasText(cfg.getToken()),
                hasText(cfg.getToken()) ? "environment" : "none",
                cfg.getWindow(),
                cfg.getLimit(),
                cfg.getMetricsWindow(),
                cfg.getMetricsLimit(),
                cfg.getMaxResponseChars()
        );
    }

    @Transactional
    public TroubleshootingConnectorConfigResponse saveGuanceConfig(long workspaceId,
                                                                   TroubleshootingConnectorConfigRequest request) {
        if (request == null) {
            throw new MateClawException("Guance connector config request is required");
        }
        TroubleshootingConnectorConfigEntity entity = find(workspaceId, GUANCE)
                .orElseGet(() -> {
                    TroubleshootingConnectorConfigEntity created = new TroubleshootingConnectorConfigEntity();
                    created.setWorkspaceId(workspaceId);
                    created.setProvider(GUANCE);
                    created.setName("观测云");
                    created.setDefaultConfig(1);
                    created.setDeleted(0);
                    return created;
                });

        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String baseUrl = blankToNull(request.baseUrl());
        if (enabled && !hasText(baseUrl)) {
            throw new MateClawException("Guance baseUrl is required when connector is enabled");
        }

        entity.setEnabled(enabled ? 1 : 0);
        entity.setBaseUrl(baseUrl);
        entity.setSyntheticsPath(value(request.syntheticsPath(), "/api/v1/df/query_data_v1"));
        entity.setMetricsPath(value(request.metricsPath(), "/api/v1/df/query_data_v1"));
        entity.setTokenHeader(value(request.tokenHeader(), "DF-API-KEY"));
        entity.setTokenPrefix(request.tokenPrefix() == null ? "" : request.tokenPrefix());
        entity.setTimeWindow(value(request.window(), "alert_time +/- 15m"));
        entity.setSyntheticsLimit(positive(request.syntheticsLimit(), 20));
        entity.setMetricsWindow(value(request.metricsWindow(), "alert_time +/- 15m"));
        entity.setMetricsLimit(positive(request.metricsLimit(), 50));
        entity.setMaxResponseChars(positive(request.maxResponseChars(), 4000));
        if (Boolean.TRUE.equals(request.clearToken())) {
            entity.setToken(null);
        } else if (hasText(request.token())) {
            entity.setToken(request.token().trim());
        }
        entity.setDeleted(0);

        if (entity.getId() == null) {
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
        }
        return getGuanceConfig(workspaceId);
    }

    public TroubleshootingEvidenceProperties.Guance resolveGuance(long workspaceId) {
        TroubleshootingEvidenceProperties.Guance merged = copy(evidenceProperties.getGuance());
        Optional<TroubleshootingConnectorConfigEntity> stored = find(workspaceId, GUANCE);
        if (stored.isEmpty()) return merged;

        TroubleshootingConnectorConfigEntity entity = stored.get();
        merged.setEnabled(Integer.valueOf(1).equals(entity.getEnabled()));
        if (hasText(entity.getBaseUrl())) merged.setBaseUrl(entity.getBaseUrl().trim());
        if (hasText(entity.getSyntheticsPath())) merged.setSyntheticsPath(entity.getSyntheticsPath().trim());
        if (hasText(entity.getMetricsPath())) merged.setMetricsPath(entity.getMetricsPath().trim());
        if (hasText(entity.getToken())) merged.setToken(entity.getToken().trim());
        if (hasText(entity.getTokenHeader())) merged.setTokenHeader(entity.getTokenHeader().trim());
        if (entity.getTokenPrefix() != null) merged.setTokenPrefix(entity.getTokenPrefix());
        if (hasText(entity.getTimeWindow())) merged.setWindow(entity.getTimeWindow().trim());
        if (positive(entity.getSyntheticsLimit())) merged.setLimit(entity.getSyntheticsLimit());
        if (hasText(entity.getMetricsWindow())) merged.setMetricsWindow(entity.getMetricsWindow().trim());
        if (positive(entity.getMetricsLimit())) merged.setMetricsLimit(entity.getMetricsLimit());
        if (positive(entity.getMaxResponseChars())) merged.setMaxResponseChars(entity.getMaxResponseChars());
        return merged;
    }

    public boolean hasEnabledGuanceConfig() {
        Long count = configMapper.selectCount(new LambdaQueryWrapper<TroubleshootingConnectorConfigEntity>()
                .eq(TroubleshootingConnectorConfigEntity::getProvider, GUANCE)
                .eq(TroubleshootingConnectorConfigEntity::getEnabled, 1)
                .eq(TroubleshootingConnectorConfigEntity::getDeleted, 0));
        return count != null && count > 0;
    }

    private Optional<TroubleshootingConnectorConfigEntity> find(long workspaceId, String provider) {
        TroubleshootingConnectorConfigEntity entity = configMapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingConnectorConfigEntity>()
                        .eq(TroubleshootingConnectorConfigEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingConnectorConfigEntity::getProvider, normalize(provider))
                        .eq(TroubleshootingConnectorConfigEntity::getDeleted, 0)
                        .orderByDesc(TroubleshootingConnectorConfigEntity::getDefaultConfig)
                        .orderByDesc(TroubleshootingConnectorConfigEntity::getUpdateTime)
                        .last("LIMIT 1"));
        return Optional.ofNullable(entity);
    }

    private TroubleshootingEvidenceProperties.Guance copy(TroubleshootingEvidenceProperties.Guance source) {
        TroubleshootingEvidenceProperties.Guance copy = new TroubleshootingEvidenceProperties.Guance();
        copy.setEnabled(source.isEnabled());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setSyntheticsPath(source.getSyntheticsPath());
        copy.setMetricsPath(source.getMetricsPath());
        copy.setToken(source.getToken());
        copy.setTokenHeader(source.getTokenHeader());
        copy.setTokenPrefix(source.getTokenPrefix());
        copy.setWindow(source.getWindow());
        copy.setLimit(source.getLimit());
        copy.setMetricsWindow(source.getMetricsWindow());
        copy.setMetricsLimit(source.getMetricsLimit());
        copy.setPayloadTemplate(source.getPayloadTemplate());
        copy.setSyntheticsPayloadTemplates(source.getSyntheticsPayloadTemplates());
        copy.setMetricsPayloadTemplate(source.getMetricsPayloadTemplate());
        copy.setConnectTimeout(source.getConnectTimeout());
        copy.setReadTimeout(source.getReadTimeout());
        copy.setMaxResponseChars(source.getMaxResponseChars());
        return copy;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
