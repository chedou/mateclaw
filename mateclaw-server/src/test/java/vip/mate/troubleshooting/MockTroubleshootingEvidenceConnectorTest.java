package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.MockTroubleshootingEvidenceConnector;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockTroubleshootingEvidenceConnectorTest {

    private final MockTroubleshootingEvidenceConnector connector = new MockTroubleshootingEvidenceConnector();

    @Test
    void collectsTypedMockMetricsEvidence() {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setCaseId("case-1");
        SopRouteRequest alert = new SopRouteRequest(
                "evt-1",
                "wecom",
                "P1",
                "API 5xx",
                "firing",
                "order-api",
                "prod",
                "bwx-prod-k8s",
                null,
                null,
                null,
                "/api/orders",
                "http_5xx_rate",
                "HTTP 503 timeout",
                null,
                Map.of(),
                3
        );

        List<CollectedEvidence> evidence = connector.collect(new EvidenceCollectionRequest(
                1L,
                "case-1",
                run,
                sop(),
                alert,
                "metrics"
        ));

        assertEquals(1, evidence.size());
        assertEquals("metrics", evidence.get(0).evidenceType());
        assertEquals("mock-troubleshooting", evidence.get(0).source());
        assertTrue(evidence.get(0).summary().contains("order-api"));
        assertTrue(evidence.get(0).content().containsKey("errorRate"));
    }

    @Test
    void normalizesGuanceInfrastructureAliasesForMockFallback() {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(124L);
        run.setCaseId("case-2");

        List<CollectedEvidence> evidence = connector.collect(new EvidenceCollectionRequest(
                1L,
                "case-2",
                run,
                sop(),
                alert(),
                "guance-container"
        ));

        assertEquals(1, evidence.size());
        assertEquals("container", evidence.get(0).evidenceType());
        assertEquals("mock-troubleshooting", evidence.get(0).source());
        assertTrue(evidence.get(0).summary().contains("容器"));
        assertTrue(evidence.get(0).content().containsKey("restartCount"));
    }

    private static SopDefinition sop() {
        return new SopDefinition(
                1L,
                "api-service-5xx",
                null,
                "1.0.0",
                true,
                0L,
                "api_service",
                "http_5xx_timeout",
                SkillManifest.TroubleshootingMatch.builder().build(),
                List.of("metrics", "logs", "release"),
                List.of(),
                "sop-checklist-v1",
                "platform-sre",
                90,
                null,
                false,
                "",
                ""
        );
    }

    private static SopRouteRequest alert() {
        return new SopRouteRequest(
                "evt-2",
                "wecom",
                "P1",
                "Pod restart",
                "firing",
                "order-api",
                "prod",
                "bwx-prod-k8s",
                "default",
                "order-api-7d6c",
                "10.0.0.12",
                "/api/orders",
                "container_restart_count",
                "Pod restarted",
                null,
                Map.of(),
                3
        );
    }
}
