package vip.mate.troubleshooting.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;
import vip.mate.troubleshooting.synthesis.KnowledgeOrigin;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewState;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewWorkflowService;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayAttestation;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayService;

import java.util.List;

/**
 * Test fixture that seeds runnable demo scenarios for HTTP acceptance checks.
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
 * <p>The candidates come directly from the same server-owned replay catalog
 * that gates promotion. This avoids a second Java copy drifting away from the
 * fixture and keeps both the fixed 903001 scenario and the recorded IM1010
 * scenario on one contract path.</p>
 */
@ConditionalOnProperty(prefix = "mateclaw.troubleshooting.demo", name = "enabled",
        havingValue = "true")
public class TroubleshootingDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TroubleshootingDemoSeeder.class);

    private static final List<String> SELECTORS =
            List.of("csdp:903001", "csdp:IM1010", "csdp:scenario:message_send_failed",
                    "csdp:scenario:gateway_timeout", "csdp:scenario:auth_token_rejected",
                    "csdp:scenario:mq_backlog", "csdp:scenario:db_pool_saturated");

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
        for (String selector : SELECTORS) {
            seed(workspaceId, selector);
        }
    }

    static List<String> selectors() {
        return SELECTORS;
    }

    private void seed(long workspaceId, String selector) {
        SopEntry candidate;
        try {
            candidate = replays.exampleCandidate(selector);
        } catch (RuntimeException missingExample) {
            log.warn("[ts-demo] demo candidate unavailable for {}: {}",
                    selector, missingExample.getMessage());
            return;
        }

        try {
            if (sops.find(workspaceId, candidate.system(), candidate.errorCode()) != null) {
                log.info("[ts-demo] demo playbook already routeable: workspace={} route={}",
                        workspaceId, candidate.routingKey());
                return;
            }
        } catch (RuntimeException probeFailure) {
            log.debug("[ts-demo] existing playbook probe failed for {}, continuing",
                    selector, probeFailure);
        }

        try {
            registerIfAbsent(workspaceId, candidate);
            ManualPlaybookReplayAttestation attestation = replays.run(
                    workspaceId, candidate.sopId(), ACTOR);
            if (attestation.status() != ManualPlaybookReplayAttestation.Status.PASSED) {
                // Fail closed and say why. Promoting anyway would make the demo
                // the one place in this system where replay proof is optional.
                log.warn("[ts-demo] replay did not pass for {} ({});"
                                + " leaving the candidate unpromoted."
                                + " The route stays missing and the smoke script will report it.",
                        selector, attestation.failureCodes());
                return;
            }

            KnowledgeReviewState review = reviews.start(
                    workspaceId, KnowledgeOrigin.MANUAL,
                    candidate.sopId(), 0, ACTOR, REASON);
            reviews.approve(
                    workspaceId, KnowledgeOrigin.MANUAL,
                    candidate.sopId(), review.version(), ACTOR, REASON);
            log.info("[ts-demo] seeded fixture-backed demo playbook: workspace={} route={}"
                            + " — approved by {} against replay suite {}; diagnoses are marked"
                            + " fixtureMode and are not evidence that the real observability"
                            + " source has been verified",
                    workspaceId, candidate.routingKey(), ACTOR, attestation.suiteId());
        } catch (RuntimeException failure) {
            // Seeding must never take the application down: an operator with a
            // real workspace should still boot even if the demo route collides.
            log.warn("[ts-demo] demo seeding skipped for {}: {}",
                    selector, failure.getMessage());
        }
    }

    private void registerIfAbsent(long workspaceId, SopEntry candidate) {
        if (sops.findBySopId(workspaceId, candidate.sopId()) != null) {
            return;
        }
        sops.register(workspaceId, candidate);
    }
}
