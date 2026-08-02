package vip.mate.troubleshooting.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.CriterionOutcome;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayEvaluation;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayEvaluator;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayFingerprint;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplaySuite;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplaySuiteCatalog;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the demo playbook to the two server-owned artifacts it must satisfy:
 * the recorded fixture it runs against, and the replay suite that gates its
 * promotion.
 *
 * <p>A drift against the fixture does not throw: the criteria simply evaluate
 * as {@code UNEVALUATED} and the scenario quietly stops concluding anything.
 * That silence is the whole reason the demo path needs a test of its own — an
 * operator would see "证据不足" and have no way to tell it apart from a genuine
 * evidence gap.</p>
 *
 * <p>A drift against the replay suite is louder but easier to misread: the
 * seeder logs a skip and the route stays missing, which looks identical to
 * "the demo profile is off".</p>
 */
class TroubleshootingDemoSeederTest {

    private static final String FIXTURE =
            "/troubleshooting/evidence/recorded-replay-catalog.json";

    @Test
    @DisplayName("演示种子覆盖三条服务端场景：夹具 903001、录制 IM1010、无码场景 message_send_failed")
    void demoSelectorsAreOwnedByTheReplayCatalog() {
        assertThat(TroubleshootingDemoSeeder.selectors())
                .containsExactly("csdp:903001", "csdp:IM1010",
                        "csdp:scenario:message_send_failed");
        assertThat(candidates())
                .extracting(SopEntry::routingKey)
                .containsExactly("csdp:903001", "csdp:IM1010",
                        "csdp:scenario:message_send_failed");
    }

    @Test
    @DisplayName("每条证据请求都能在回放样本里找到对应记录")
    void everyEvidenceRequestIsAnswerableByTheFixture() throws Exception {
        Set<String> fixtureRequestIds = fixtureRecords().stream()
                .map(record -> record.get("requestId").asText())
                .collect(Collectors.toSet());

        List<String> requested = candidates().stream()
                .flatMap(candidate -> candidate.evidenceRequests().stream())
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

        for (SopEntry candidate : candidates()) {
            for (AnomalyCriterion criterion : candidate.anomalyCriteria()) {
                Set<String> available = observedFields.get(criterion.sourceRequestId());
                assertThat(available)
                        .as("criterion %s points at a request the fixture does not answer",
                                criterion.signal())
                        .isNotNull();
                assertThat(available)
                        .as("criterion %s reads a field the fixture never observes",
                                criterion.signal())
                        .containsAll(fieldsOf(criterion.rule()));
            }
        }
    }

    @Test
    @DisplayName("在回放观测值上，慢查询规则命中而实例不可达被排除")
    void theFixtureMakesTheIntendedRuleWin() throws Exception {
        Map<String, Object> observed = observedOf("EV-2");
        CriterionEvaluator evaluator = new CriterionEvaluator();

        Map<String, AnomalyCriterion> bySignal = candidateFor("csdp:903001")
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

    /**
     * This assertion used to forbid {@code MANUAL_WRITE} in seeded Playbooks
     * outright. That was the wrong invariant, and it was actively harmful.
     *
     * <p>A {@code MANUAL_WRITE} action is not a dangerous capability here: it is
     * {@code BLOCKED} from the moment it is registered, MateClaw has no
     * production-write executor, and approval only advances the state machine.
     * Banning it from the demo did not make anything safer — it meant the
     * product's central guarantee could never be <em>demonstrated</em>, because
     * no Playbook existed to walk it on. The scenario smoke needs exactly one.</p>
     *
     * <p>So the ban is replaced by the invariant that actually matters: a seeded
     * write must be in the legal blocked state — approval required, pending, and
     * blocked. That is strictly stronger than "there are none".</p>
     */
    @Test
    @DisplayName("种子 playbook 只以 candidate 注册；生产写动作必须处于合法的阻塞态")
    void seededPlaybookStaysCandidateAndCannotExecute() {
        assertThat(candidates()).allSatisfy(playbook -> {
            assertThat(playbook.status()).isEqualTo("candidate");
            assertThat(playbook.verified()).isFalse();
            assertThat(playbook.actions()).allSatisfy(action -> {
                if (action.actionType() == ActionType.MANUAL_WRITE) {
                    assertThat(action.requiresApproval())
                            .as("生产写必须要求人工批准").isTrue();
                    assertThat(action.approvalStatus())
                            .isEqualTo(ApprovalStatus.PENDING);
                    assertThat(action.executionStatus())
                            .as("生产写从注册那一刻起就必须是 BLOCKED")
                            .isEqualTo(ExecutionStatus.BLOCKED);
                } else {
                    assertThat(action.actionType())
                            .isIn(ActionType.AUTO_READONLY, ActionType.HUMAN_CONTACT);
                }
            });
        });
    }

    /**
     * The gate the first version of the seeder tried to skip.
     *
     * <p>Approval is not a status flip: it requires a passing run of the
     * server-owned replay suite. Without this the seeder would log a skip at
     * boot and the demo route would silently stay missing.</p>
     */
    @Test
    @DisplayName("每个演示候选都能通过自己的服务端回放套件，因而可被正常晋升")
    void seededPlaybooksPassTheSuitesThatGateTheirPromotion() {
        for (String selector : TroubleshootingDemoSeeder.selectors()) {
            ManualPlaybookReplaySuite suite = suiteFor(selector);
            ManualPlaybookReplayEvaluation evaluation =
                    new ManualPlaybookReplayEvaluator(
                            new CriterionEvaluator(), new DiagnosisRuleEvaluator())
                            .evaluate(candidateFor(selector), suite);

            assertThat(evaluation.failureCodes()).as(selector).isEmpty();
            assertThat(evaluation.passed()).as(selector).isTrue();
            assertThat(evaluation.positiveTotal())
                    .as("%s 一套只有正例的回放证明不了任何排除能力", selector).isPositive();
            assertThat(evaluation.negativeOrAbstainTotal())
                    .as("%s 必须有反例/弃权例，否则规则永远赢", selector).isPositive();
        }
    }

    @Test
    @DisplayName("回放套件覆盖命中、排除与弃权三种处置，而不是只证明会命中")
    void theSuitesCoverAllThreeDispositions() {
        for (String selector : TroubleshootingDemoSeeder.selectors()) {
            List<ManualPlaybookReplaySuite.Disposition> expected = suiteFor(selector).cases().stream()
                    .map(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                    .distinct()
                    .toList();

            assertThat(expected).as(selector).containsExactlyInAnyOrder(
                    ManualPlaybookReplaySuite.Disposition.MATCHED,
                    ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                    ManualPlaybookReplaySuite.Disposition.ABSTAINED);
        }
    }

    private static ManualPlaybookReplaySuite suiteFor(String selectorKey) {
        return catalog().find(selectorKey).orElseThrow().suite();
    }

    private static SopEntry candidateFor(String selectorKey) {
        return suiteFor(selectorKey).exampleCandidate();
    }

    private static List<SopEntry> candidates() {
        return TroubleshootingDemoSeeder.selectors().stream()
                .map(TroubleshootingDemoSeederTest::candidateFor)
                .toList();
    }

    private static ManualPlaybookReplaySuiteCatalog catalog() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new ManualPlaybookReplaySuiteCatalog(
                mapper,
                new ManualPlaybookReplayFingerprint(mapper),
                new ManualPlaybookReplayEvaluator(
                        new CriterionEvaluator(), new DiagnosisRuleEvaluator()));
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
