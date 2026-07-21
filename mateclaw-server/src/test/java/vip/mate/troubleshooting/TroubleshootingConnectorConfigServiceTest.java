package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.dto.TroubleshootingConnectorConfigRequest;
import vip.mate.troubleshooting.evidence.TroubleshootingEvidenceProperties;
import vip.mate.troubleshooting.model.TroubleshootingConnectorConfigEntity;
import vip.mate.troubleshooting.repository.TroubleshootingConnectorConfigMapper;
import vip.mate.troubleshooting.service.TroubleshootingConnectorConfigService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TroubleshootingConnectorConfigServiceTest {

    @Test
    void resolveGuancePrefersDatabaseConfigOverEnvironmentDefaults() {
        TroubleshootingConnectorConfigMapper mapper = mock(TroubleshootingConnectorConfigMapper.class);
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        props.getGuance().setEnabled(false);
        props.getGuance().setBaseUrl("http://env-guance");
        props.getGuance().setToken("env-token");

        TroubleshootingConnectorConfigEntity entity = guanceEntity();
        entity.setBaseUrl("http://df-openapi.prd.sangfor.com");
        entity.setToken("db-token");
        entity.setTokenHeader("DF-API-KEY");
        entity.setTokenPrefix("");
        entity.setSyntheticsPath("/api/v1/df/query_data_v1");
        when(mapper.selectOne(any())).thenReturn(entity);

        TroubleshootingConnectorConfigService service = new TroubleshootingConnectorConfigService(mapper, props);
        TroubleshootingEvidenceProperties.Guance resolved = service.resolveGuance(1L);

        assertTrue(resolved.isEnabled());
        assertEquals("http://df-openapi.prd.sangfor.com", resolved.getBaseUrl());
        assertEquals("/api/v1/df/query_data_v1", resolved.getSyntheticsPath());
        assertEquals("DF-API-KEY", resolved.getTokenHeader());
        assertEquals("", resolved.getTokenPrefix());
        assertEquals("db-token", resolved.getToken());
    }

    @Test
    void saveGuanceKeepsExistingTokenWhenRequestTokenIsBlank() {
        TroubleshootingConnectorConfigMapper mapper = mock(TroubleshootingConnectorConfigMapper.class);
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        TroubleshootingConnectorConfigEntity entity = guanceEntity();
        entity.setToken("existing-token");
        when(mapper.selectOne(any())).thenReturn(entity);

        TroubleshootingConnectorConfigService service = new TroubleshootingConnectorConfigService(mapper, props);
        service.saveGuanceConfig(1L, new TroubleshootingConnectorConfigRequest(
                true,
                "http://df-openapi.prd.sangfor.com",
                "/api/v1/df/query_data_v1",
                "/api/v1/metrics/query",
                "",
                false,
                "DF-API-KEY",
                "",
                "alert_time +/- 15m",
                20,
                "alert_time +/- 15m",
                50,
                4000
        ));

        assertEquals("existing-token", entity.getToken());
        assertEquals("http://df-openapi.prd.sangfor.com", entity.getBaseUrl());
        verify(mapper).updateById(entity);
    }

    @Test
    void getGuanceConfigDoesNotExposeTokenValue() {
        TroubleshootingConnectorConfigMapper mapper = mock(TroubleshootingConnectorConfigMapper.class);
        TroubleshootingEvidenceProperties props = new TroubleshootingEvidenceProperties();
        TroubleshootingConnectorConfigEntity entity = guanceEntity();
        entity.setToken("existing-token");
        when(mapper.selectOne(any())).thenReturn(entity);

        TroubleshootingConnectorConfigService service = new TroubleshootingConnectorConfigService(mapper, props);
        var response = service.getGuanceConfig(1L);

        assertTrue(response.tokenConfigured());
        assertEquals("database", response.tokenSource());
        assertTrue(response.persisted());
        assertFalse(response.toString().contains("existing-token"));
    }

    private static TroubleshootingConnectorConfigEntity guanceEntity() {
        TroubleshootingConnectorConfigEntity entity = new TroubleshootingConnectorConfigEntity();
        entity.setId(100L);
        entity.setWorkspaceId(1L);
        entity.setProvider("guance");
        entity.setName("观测云");
        entity.setEnabled(1);
        entity.setDefaultConfig(1);
        entity.setDeleted(0);
        entity.setSyntheticsLimit(20);
        entity.setMetricsLimit(50);
        entity.setMaxResponseChars(4000);
        return entity;
    }
}
