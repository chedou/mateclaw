package vip.mate.troubleshooting.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
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
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;
import vip.mate.troubleshooting.synthesis.KnowledgeOrigin;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewState;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewWorkflowService;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayAttestation;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayService;

import java.util.List;
import java.util.Map;

/**
 * Seeds one runnable demo scenario so the system has a path a person can walk.
 *
 * <p><b>Why this exists.</b> Every gate in this domain is fail-closed on
 * purpose, and each one is individually right. Their conjunction, however, was
 * that a fresh checkout could not produce a single diagnosis: no evidence
 * source is enabled by default and no Playbook ships with the repository, so
 * every report necessarily missed the route. A system nobody can run once is
 * also a system whose safety has never actually been exercised — fail-closed is
 * only tested when someone tries to open the door.</p>
 *
 * <p><b>It walks the real promotion pipeline, it does not bypass it.</b> An
 * earlier version of this class registered the candidate and then flipped its
 * status to {@code approved}. That is rejected at runtime, and rightly so:
 * {@code updateStatus} fails closed on candidate approval because approval must
 * pass the eligibility gate and must create a new version. So the seeder does
 * what a reviewer does — register the candidate, run the server-owned fixed
 * replay suite against it, then start and approve a knowledge review. If the
 * replay does not pass, the review is not eligible and nothing is promoted; the
 * demo route simply stays missing and the smoke script says so.</p>
 *
 * <p><b>What it therefore cannot hide.</b> The approval is recorded in the
 * knowledge-review ledger under {@link #ACTOR}, not under a person's name, so
 * the audit trail never suggests a human vouched for this content. The Playbook
 * is bound to the Recorded Replay fixture, so every diagnosis it produces is
 * marked {@code fixtureMode}. It is off unless
 * {@code mateclaw.troubleshooting.demo.enabled=true} is set explicitly, and it
 * never touches a real observability binding.</p>
 *
 * <p>The signals below intentionally mirror
 * {@code troubleshooting/evidence/recorded-replay-903001.json} request-for-request,
 * and the replay suite for {@code csdp:903001} mirrors them again. If they drift
 * apart the criteria evaluate as {@code UNEVALUATED} rather than failing loudly,
 * which is exactly the silent outcome {@code TroubleshootingDemoSeederTest} and
 * the smoke script exist to catch.</p>
 */
@Component
@ConditionalOnProperty(prefix = "mateclaw.troubleshooting.demo", name = "enabled",
        havingValue = "true")
public class TroubleshootingDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TroubleshootingDemoSeeder.class);

    static final String SYSTEM = "CSDP";
    static final String ERROR_CODE = "903001";
    static final String SERVICE = "order-svc";
    static final String SOP_ID = "demo-csdp-903001";

    /**
     * Deliberately not a person. Whoever reads the review ledger later must be
     * able to see at a glance that no human reviewed this content.
     */
    static final String ACTOR = "ts-demo-seeder";

    private static final String REASON =
            "fixture demo seed: approved against the bundled replay suite only, "
                    + "not a human review and not evidence that any real source was verified";

    private final TroubleshootingSopPersistenceService sops;
    private final ManualPlaybookReplayService replays;
    private final KnowledgeReviewWorkflowService reviews;
    private final TroubleshootingDemoProperties properties;

    public TroubleshootingDemoSeeder(
            TroubleshootingSopPersistenceService sops,
            ManualPlaybookReplayService replays,
            KnowledgeReviewWorkflowService reviews,
            TroubleshootingDemoProperties properties) {
        this.sops = sops;
        this.replays = replays;
        this.reviews = reviews;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        long workspaceId = properties.getWorkspaceId();
        try {
            if (sops.find(workspaceId, SYSTEM, ERROR_CODE) != null) {
                log.info("[ts-demo] demo playbook already routeable: workspace={} route={}:{}",
                        workspaceId, SYSTEM, ERROR_CODE);
                return;
            }
        } catch (RuntimeException probeFailure) {
            log.debug("[ts-demo] existing playbook probe failed, continuing", probeFailure);
        }

        try {
            registerIfAbsent(workspaceId);
            ManualPlaybookReplayAttestation attestation = replays.run(
                    workspaceId, SOP_ID, ACTOR);
            if (attestation.status() != ManualPlaybookReplayAttestation.Status.PASSED) {
                // Fail closed and say why. Promoting anyway would make the demo
                // the one place in this system where replay proof is optional.
                log.warn("[ts-demo] replay did not pass ({}); leaving the candidate unpromoted."
                                + " The route stays missing and the smoke script will report it.",
                        attestation.failureCodes());
                return;
            }

            KnowledgeReviewState review = reviews.start(
                    workspaceId, KnowledgeOrigin.MANUAL, SOP_ID, 0, ACTOR, REASON);
            reviews.approve(
                    workspaceId, KnowledgeOrigin.MANUAL, SOP_ID, review.version(), ACTOR, REASON);
            log.info("[ts-demo] seeded fixture-backed demo playbook: workspace={} route={}:{}"
                            + " — approved by {} against replay suite {}; diagnoses are marked"
                            + " fixtureMode and are not evidence that the real observability"
                            + " source has been verified",
                    workspaceId, SYSTEM, ERROR_CODE, ACTOR, attestation.suiteId());
        } catch (RuntimeException failure) {
            // Seeding must never take the application down: an operator with a
            // real workspace should still boot even if the demo route collides.
            log.warn("[ts-demo] demo seeding skipped: {}", failure.getMessage());
        }
    }

    private void registerIfAbsent(long workspaceId) {
        if (sops.findBySopId(workspaceId, SOP_ID) != null) {
            return;
        }
        sops.register(workspaceId, playbook());
    }

    /** Mirrors the recorded-replay fixture and the {@code csdp:903001} replay suite. */
    static SopEntry playbook() {
        List<EvidenceRequest> evidence = List.of(
                new EvidenceRequest("EV-1", "log_count", "错误码日志计数",
                        Map.of("errorCode", ERROR_CODE), "-15m", true),
                new EvidenceRequest("EV-2", "metric", "连接池与慢查询水位",
                        Map.of("target", "mongo"), "-15m", true),
                new EvidenceRequest("EV-3", "trace", "失败跳点",
                        Map.of("service", SERVICE), "-15m", false));

        List<AnomalyCriterion> criteria = List.of(
                new AnomalyCriterion("pool_exhausted", "EV-2",
                        "连接池占用率超过 95%",
                        new Criterion.RatioOfSumGt(
                                "connections_current", "connections_available", 0.95)),
                new AnomalyCriterion("slow_query_burst", "EV-2",
                        "慢查询数超过基线 3 倍",
                        new Criterion.MultipleGt("slow_query_count", "baseline_slow", 3)),
                new AnomalyCriterion("instance_unreachable", "EV-2",
                        "实例不可达",
                        new Criterion.BooleanEquals("reachable", false)),
                new AnomalyCriterion("error_burst", "EV-1",
                        "错误码日志出现",
                        new Criterion.NumericGte("count", 1)));

        // Order matters: the unreachable-instance rule is listed first precisely
        // so the replay suite proves both outcomes — it wins when the instance is
        // genuinely unreachable, and it is EXCLUDED (not merely untested) when
        // the instance answers.
        List<DiagnosisRule> rules = List.of(
                new DiagnosisRule("R1", List.of("instance_unreachable"),
                        "数据库实例不可达",
                        "实例不可达导致连接失败", Confidence.HIGH, false),
                new DiagnosisRule("R2", List.of("pool_exhausted", "slow_query_burst"),
                        "慢查询占满连接池",
                        "慢查询长时间占用连接，新请求拿不到连接", Confidence.HIGH, false),
                new DiagnosisRule("R3", List.of("error_burst"),
                        "错误码集中出现，原因待确认",
                        "只观察到错误码集中出现，尚不足以定位到具体原因",
                        Confidence.LOW, true));

        List<RecommendedAction> actions = List.of(
                new RecommendedAction("A1", ActionType.HUMAN_CONTACT,
                        "联系 DBA 确认慢查询来源",
                        "由 DBA 在平台之外处理；平台只提供证据，不执行任何生产变更。",
                        false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.PENDING));

        return new SopEntry(
                SOP_ID, SopEntry.CURRENT_CONTRACT_VERSION, SYSTEM, ERROR_CODE, SERVICE,
                "工单库连接池被慢查询占满",
                "慢查询长时间持有连接，连接池耗尽后新请求拿不到连接",
                "database", "工单平台组",
                "candidate", false,
                evidence, criteria, rules, actions);
    }
}
