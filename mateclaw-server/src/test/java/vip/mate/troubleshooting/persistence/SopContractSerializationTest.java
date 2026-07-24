package vip.mate.troubleshooting.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SopContractSerializationTest {

    @Test
    void sealedCriteriaSurviveJsonRoundTrip() throws Exception {
        SopEntry sop = new SopEntry(
                "mongo/conn-saturated",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "903001",
                "csdp-wechat",
                "MongoDB 连接异常",
                "连接饱和",
                "依赖异常",
                "DBA 值班",
                "approved",
                true,
                List.of(new EvidenceRequest(
                        "mongo-metrics",
                        "metric",
                        "判断连接饱和",
                        targetWithNull(),
                        "-15m",
                        true)),
                List.of(
                        new AnomalyCriterion(
                                "conn_saturated",
                                "mongo-metrics",
                                "ratio > 0.9",
                                new Criterion.RatioOfSumGt("current", "available", 0.9)),
                        new AnomalyCriterion(
                                "mongo_unreachable",
                                "mongo-metrics",
                                "reachable=false",
                                new Criterion.BooleanEquals("reachable", false))),
                List.of(new DiagnosisRule(
                        "connection-saturated",
                        List.of("conn_saturated"),
                        "MongoDB 连接数饱和",
                        "指标证据支持",
                        Confidence.HIGH,
                        false)),
                List.of(
                        new RecommendedAction(
                                "retain-evidence",
                                ActionType.AUTO_READONLY,
                                "保留证据",
                                "只读",
                                false,
                                ApprovalStatus.NOT_REQUIRED,
                                ExecutionStatus.COMPLETED),
                        RecommendedAction.manualWrite(
                                "restart-mongodb",
                                "外部人工重启 MongoDB",
                                "MateClaw 不执行")));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        String json = mapper.writeValueAsString(sop);
        SopEntry restored = mapper.readValue(json, SopEntry.class);

        assertEquals("csdp:903001", restored.routingKey());
        assertTrue(restored.operational());
        assertEquals(null, restored.evidenceRequests().getFirst().target().get("optional"));
        assertInstanceOf(Criterion.RatioOfSumGt.class, restored.anomalyCriteria().getFirst().rule());
        assertInstanceOf(Criterion.BooleanEquals.class, restored.anomalyCriteria().getLast().rule());
        assertEquals(ExecutionStatus.BLOCKED, restored.actions().getLast().executionStatus());
    }

    private Map<String, Object> targetWithNull() {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("metric", "mongodb_connection_health");
        target.put("optional", null);
        return target;
    }
}
