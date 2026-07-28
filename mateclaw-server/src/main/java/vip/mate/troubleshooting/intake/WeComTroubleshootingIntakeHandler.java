package vip.mate.troubleshooting.intake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessagePreRouteDeliveryException;
import vip.mate.channel.ChannelMessagePreRouteHandler;
import vip.mate.channel.model.ChannelEntity;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Normal-message entry for WeCom troubleshooting intake.
 *
 * <p>This is deliberately not a WeComCardKind: card kinds only receive
 * template-card click events, while the first-line product entry is a normal
 * group @ message. The existing adapter still owns transport, signature,
 * attachment download and access policy; this handler only sees the normalized
 * ChannelMessage after those checks.</p>
 */
@Slf4j
@Component
public class WeComTroubleshootingIntakeHandler
        implements ChannelMessagePreRouteHandler {

    public static final String ENABLED_CONFIG_KEY = "troubleshooting_intake_enabled";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final TroubleshootingIntakeSessionService intakeService;
    private final ObjectMapper objectMapper;

    public WeComTroubleshootingIntakeHandler(
            TroubleshootingIntakeSessionService intakeService,
            ObjectMapper objectMapper) {
        this.intakeService = intakeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(
            ChannelMessage message,
            ChannelAdapter adapter,
            ChannelEntity channelEntity) {
        return message != null
                && adapter != null
                && "wecom".equals(adapter.getChannelType())
                && channelEntity != null
                && channelEntity.getWorkspaceId() != null
                && channelEntity.getWorkspaceId() > 0
                && configEnabled(channelEntity.getConfigJson());
    }

    @Override
    public void handle(
            ChannelMessage message,
            ChannelAdapter adapter,
            ChannelEntity channelEntity) {
        String conversationRef = firstNonBlank(
                message.getChatId(), message.getSenderId());
        String target = firstNonBlank(
                message.getReplyToken(), message.getChatId(), message.getSenderId());
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            throw new IllegalArgumentException(
                    "WeCom troubleshooting intake requires a source message id");
        }
        if (conversationRef == null || message.getSenderId() == null
                || message.getSenderId().isBlank()) {
            throw new IllegalArgumentException(
                    "WeCom troubleshooting intake lacks conversation/reporter identity");
        }
        if (target == null) {
            throw new IllegalArgumentException(
                    "WeCom troubleshooting intake lacks a reply target");
        }
        Instant receivedAt = message.getTimestamp() == null
                ? Instant.now()
                : message.getTimestamp().atZone(BUSINESS_ZONE).toInstant();
        List<IntakeAttachmentRef> attachments = IntakeAttachmentRef.fromContentParts(
                message.getContentParts(), message.getMessageId());
        IntakeDecision decision = intakeService.accept(new IntakeMessageEnvelope(
                channelEntity.getWorkspaceId(),
                "wecom",
                message.getMessageId(),
                conversationRef,
                message.getSenderId(),
                message.getContent(),
                attachments,
                receivedAt));
        try {
            adapter.renderAndSend(target, decision.prompt());
        } catch (RuntimeException deliveryError) {
            throw new ChannelMessagePreRouteDeliveryException(
                    "intake " + decision.intakeSessionId()
                            + " persisted but acknowledgement delivery failed",
                    deliveryError);
        }
        log.info("[ts-intake] WeCom message accepted: workspace={} intake={} status={} duplicate={} outOfOrder={}",
                channelEntity.getWorkspaceId(), decision.intakeSessionId(), decision.status(),
                decision.duplicate(), decision.outOfOrder());
    }

    private boolean configEnabled(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(
                    configJson, new TypeReference<>() {});
            Object raw = config.get(ENABLED_CONFIG_KEY);
            return Boolean.TRUE.equals(raw)
                    || raw instanceof String text && Boolean.parseBoolean(text.trim());
        } catch (Exception error) {
            log.warn("[ts-intake] invalid channel config; intake remains disabled: {}",
                    error.getMessage());
            return false;
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
