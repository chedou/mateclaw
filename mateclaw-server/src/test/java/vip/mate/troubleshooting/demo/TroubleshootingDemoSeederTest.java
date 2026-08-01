package vip.mate.troubleshooting.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.SopEntry;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the demo playbook to the recorded fixture it is meant to run against.
 *
 * <p>A drift between the two does not throw: the criteria simply evaluate as
 * {@code UNEVALUATED} and the scenario quietly stops concluding anything. That
 * silence is the whole reason the demo path needs a test of its own — an
 * operator would see "证据不足" and have no way to tell it apart from a genuine
 * evidence gap.</p>
 */
class TroubleshootingDemoSeederTest {

    private static final String FIXTURE =
            "/troubleshooting/evidence/recorded-replay-903001.json";

    @Test
    @DisplayName("每条证据请求都能在回放样本里找到对应记录")
    void everyEvidenceRequestIsAnswerableByTheFixture() throws Exception {
        Set<String> fixtureRequestIds = fixtureRecords().stream()
                .map(record -> record.get("requestId").asText())
                .collect(Collectors.toSet());

        List<String> requested = TroubleshootingDemoSeeder.playbook().evidenceRequests().stream()
                .map(request -> request.requestId())
                .toList();

        assertThat(requested).isNotEmpty();
        assertThat(fixtureRequestIds).containsAll(requested);
    }

    @Test
    @DisplayName("判据引用的字段都存在于对应记录的 observed 里")
    void criteriaOnlyReferenceObservedFields() throws Exception {
        Map<String, Set<String>> observedFields = new LinkedHashMap<>();
        for (JsonNode record : fixtureRecords()) {
            observedFields.put(record.get("requestId").asText(),
                    fieldNames(record.get("observed")));
        }

        for (AnomalyCriterion criterion : TroubleshootingDemoSeeder.playbook().anomalyCriteria()) {
            Set<String> available = observedFields.get(criterion.sourceRequestId());
            assertThat(available)
                    .as("criterion %s points at a request the fixture does not answer",
                            criterion.signal())
                    .isNotNull();
            assertThat(available)
                    .as("criterion %s reads a field the fixture never observes", criterion.signal())
                    .containsAll(fieldsOf(criterion.rule()));
        }
    }

    @Test
    @DisplayName("在回放观测值上，慢查询规则命中而实例不可达被排除")
    void theFixtureMakesTheIntendedRuleWin() throws Exception {
        Map<String, Object> observed = observedOf("EV-2");
        CriterionEvaluator evaluator = new CriterionEvaluator();

        Map<String, AnomalyCriterion> bySignal = TroubleshootingDemoSeeder.playbook()
                .anomalyCriteria().stream()
                .collect(Collectors.toMap(AnomalyCriterion::signal, criterion -> criterion,
                        (first, second) -> first, LinkedHashMap::new));

        assertThat(evaluator.evaluate(bySignal.get("pool_exhausted").rule(), observed))
                .as("连接池占用率应当成立").isEqualTo(CriterionOutcome.SATISFIED);
        assertThat(evaluator.evaluate(bySignal.get("slow_query_burst").rule(), observed))
                .as("慢查询超基线应当成立").isEqualTo(CriterionOutcome.SATISFIED);
        assertThat(evaluator.evaluate(bySignal.get("instance_unreachable").rule(), observed))
                .as("实例可达：该假设应当是 EXCLUDED（真的排除），而不是 UNEVALUATED（没验过）")
                .isEqualTo(CriterionOutcome.EXCLUDED);
    }

    @Test
    @DisplayName("种子 playbook 只以 candidate 注册，且不含任何生产写动作")
    void seededPlaybookStaysCandidateAndReadOnly() {
        SopEntry playbook = TroubleshootingDemoSeeder.playbook();
        assertThat(playbook.status()).isEqualTo("candidate");
        assertThat(playbook.verified()).isFalse();
        assertThat(playbook.actions())
                .allSatisfy(action -> assertThat(action.actionType())
                        .isNotEqualTo(ActionType.MANUAL_WRITE));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> fieldsOf(Criterion rule) {
        return switch (rule) {
            case Criterion.NumericGte value -> Set.of(value.field());
            case Criterion.MissingOrLte value -> Set.of(value.presenceField(), value.field());
            case Criterion.RatioOfSumGt value ->
                    Set.of(value.numeratorField(), value.addendField());
            case Criterion.MultipleGt value -> Set.of(value.field(), value.baselineField());
            case Criterion.ContainsAndIn value ->
                    Set.of(value.containsField(), value.membershipField());
            case Criterion.BooleanEquals value -> Set.of(value.field());
        };
    }

    private static Map<String, Object> observedOf(String requestId) throws Exception {
        for (JsonNode record : fixtureRecords()) {
            if (requestId.equals(record.get("requestId").asText())) {
                return new ObjectMapper().convertValue(record.get("observed"), Map.class);
            }
        }
        throw new IllegalStateException("fixture has no record " + requestId);
    }

    private static List<JsonNode> fixtureRecords() throws Exception {
        try (InputStream stream = TroubleshootingDemoSeederTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(stream).as("recorded replay fixture must ship with the module").isNotNull();
            JsonNode root = new ObjectMapper().readTree(stream);
            List<JsonNode> records = new java.util.ArrayList<>();
            root.get("records").forEach(records::add);
            return records;
        }
    }
}
