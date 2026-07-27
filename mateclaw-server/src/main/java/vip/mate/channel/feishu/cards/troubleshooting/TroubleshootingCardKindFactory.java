package vip.mate.channel.feishu.cards.troubleshooting;

import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import org.springframework.stereotype.Component;
import vip.mate.channel.feishu.cards.FeishuCardKind;
import vip.mate.troubleshooting.card.CardOperatorResolver;
import vip.mate.troubleshooting.card.TroubleshootingCardAction;
import vip.mate.troubleshooting.card.TroubleshootingCardHandler;
import vip.mate.troubleshooting.service.DiagnosisLifecycleService;

import java.util.Map;

/**
 * Registers the troubleshooting card kind with the Feishu dispatcher.
 *
 * <p>Card kinds are keyed by a disjoint action prefix, so the domain owns
 * {@code ts.} and its clicks route here without touching tool-guard's cards.
 * The distinction matters: a tool-guard approval replays the tool call it
 * authorized, whereas a troubleshooting card only advances a state machine and
 * executes nothing.</p>
 *
 * <p><b>Outbound is not wired.</b> The platform's {@code FeishuCardRenderer}
 * seam renders an {@code ApprovalNotice}, which is tool-guard's shape rather
 * than a diagnosis, so this kind cannot reuse it. Sending a diagnosis card also
 * needs a decision this design has not made yet — which chat receives which
 * system's incidents. Rather than register a renderer that would produce a
 * misleading card, this supplies one that fails loudly if something ever calls
 * it, and inbound handling works today.</p>
 */
@Component("feishuTroubleshootingCardKindFactory")
public class TroubleshootingCardKindFactory {

    public static final String KIND_NAME = "troubleshooting_diagnosis";
    public static final String ACTION_PREFIX = TroubleshootingCardAction.ACTION_PREFIX;

    /** Default workspace until incident-to-chat binding lands. */
    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final DiagnosisLifecycleService lifecycleService;
    private final CardOperatorResolver operatorResolver;

    public TroubleshootingCardKindFactory(
            DiagnosisLifecycleService lifecycleService,
            CardOperatorResolver operatorResolver) {
        this.lifecycleService = lifecycleService;
        this.operatorResolver = operatorResolver;
    }

    public FeishuCardKind create() {
        TroubleshootingCardHandler handler =
                new TroubleshootingCardHandler(lifecycleService, operatorResolver);
        return new FeishuCardKind(
                KIND_NAME,
                ACTION_PREFIX,
                notice -> {
                    throw new UnsupportedOperationException(
                            "troubleshooting cards do not render an ApprovalNotice; "
                                    + "outbound diagnosis cards need their own send path");
                },
                (adapter, data) -> {
                    Map<String, Object> value = data.getAction() == null
                            ? null
                            : data.getAction().getValue();
                    String clickerOpenId = data.getOperator() == null
                            ? null
                            : data.getOperator().getOpenId();
                    handler.handle(DEFAULT_WORKSPACE_ID, value, clickerOpenId);
                    // Feishu leaves the card as-is when no card update is returned,
                    // which is what we want: the console remains the source of truth.
                    return new P2CardActionTriggerResponse();
                });
    }
}
