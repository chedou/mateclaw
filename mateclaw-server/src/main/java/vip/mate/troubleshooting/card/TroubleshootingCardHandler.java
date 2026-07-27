package vip.mate.troubleshooting.card;

import lombok.extern.slf4j.Slf4j;
import vip.mate.troubleshooting.service.DiagnosisLifecycleService;

import java.util.Optional;

/**
 * Handles a click on a troubleshooting card.
 *
 * <p>Only ever advances the domain state machine. It executes no tool, and it
 * cannot: the platform has no production write executor, and the card verbs do
 * not include one. This is what keeps the domain's cards structurally different
 * from tool-guard's approve-then-replay cards, which do execute what they
 * authorize.</p>
 *
 * <p>Two refusals are deliberate and both fail closed:</p>
 * <ul>
 *   <li><b>Unlinked clicker.</b> A chat id that is not linked to a MateClaw user
 *       cannot be held responsible for a transition, so the click is rejected
 *       and the operator is pointed at the console.</li>
 *   <li><b>Anything beyond confirming.</b> Approving a production write needs a
 *       reviewed reason, and closing needs a structured outcome; a card summary
 *       is not enough context for either, so they stay in the console.</li>
 * </ul>
 *
 * <p>Kept free of Feishu SDK types so the decision logic is testable and can be
 * reused by another channel's card layer.</p>
 */
@Slf4j
public class TroubleshootingCardHandler {

    private final DiagnosisLifecycleService lifecycleService;
    private final CardOperatorResolver operatorResolver;

    public TroubleshootingCardHandler(
            DiagnosisLifecycleService lifecycleService,
            CardOperatorResolver operatorResolver) {
        this.lifecycleService = lifecycleService;
        this.operatorResolver = operatorResolver;
    }

    /** What the channel layer should tell the clicker. */
    public record Outcome(boolean handled, boolean stateChanged, String toast) {

        static Outcome ignored() {
            return new Outcome(false, false, null);
        }

        static Outcome refused(String toast) {
            return new Outcome(true, false, toast);
        }

        static Outcome advanced(String toast) {
            return new Outcome(true, true, toast);
        }
    }

    /**
     * Applies a card click.
     *
     * @param workspaceId workspace the card belongs to
     * @param rawValue    the button payload as received
     * @param clickerOpenId the clicker's channel id
     */
    public Outcome handle(long workspaceId, java.util.Map<String, Object> rawValue, String clickerOpenId) {
        TroubleshootingCardAction action = TroubleshootingCardAction.decode(rawValue);
        if (action == null) {
            return Outcome.ignored();
        }
        if (action.verb() == TroubleshootingCardAction.Verb.OPEN) {
            return Outcome.refused("请在故障工作台查看完整判定链");
        }

        Optional<String> operator = operatorResolver.resolveFeishu(clickerOpenId);
        if (operator.isEmpty()) {
            log.warn("[ts-card] refused: chat account not linked to a MateClaw user, diagnosis={}",
                    action.diagnosisId());
            return Outcome.refused("此聊天账号未关联 MateClaw 用户，请在工作台确认");
        }

        try {
            lifecycleService.confirm(workspaceId, action.diagnosisId(), operator.get());
            return Outcome.advanced("已确认诊断结论（未执行任何操作）");
        } catch (RuntimeException e) {
            // A conflict here is normal: someone already confirmed in the console,
            // or the diagnosis abstained and needs new evidence first.
            log.info("[ts-card] confirm refused for diagnosis={} operator={}: {}",
                    action.diagnosisId(), operator.get(), e.getMessage());
            return Outcome.refused("未生效：" + e.getMessage());
        }
    }
}
