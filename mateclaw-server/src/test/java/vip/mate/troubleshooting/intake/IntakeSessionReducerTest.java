package vip.mate.troubleshooting.intake;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntakeSessionReducerTest {

    private static final Instant FIRST_MESSAGE_AT = Instant.parse("2026-07-29T02:00:00Z");

    private final IntakeSessionReducer reducer = new IntakeSessionReducer();

    @Test
    void incompleteFirstMessageBecomesAwaitingInputWithoutGuessingFields() {
        IntakeMessageEnvelope first = envelope(
                "msg-1",
                "会话消息发送失败",
                List.of(new IntakeAttachmentRef(
                        "video", "stored-video-1.mp4", "failure.mp4",
                        "video/mp4", 1024L, false)),
                FIRST_MESSAGE_AT);

        IntakeSession session = reducer.start("intake-1", first);

        assertEquals(IntakeSessionStatus.AWAITING_INPUT, session.status());
        assertEquals("会话消息发送失败", session.symptom());
        assertNull(session.system());
        assertNull(session.service());
        assertNull(session.customerRef());
        assertNull(session.occurredAt());
        assertNull(session.readyAt());
        assertEquals(List.of("system", "service", "customerRef", "occurredAt"),
                session.missingFields());
        assertEquals(1, session.attachments().size());
        assertFalse(session.attachments().getFirst().contentAnalyzed(),
                "video is reference-only in the current product boundary");
        assertTrue(IntakeDecision.from(session, false, false).prompt().contains("还需要"));
    }

    @Test
    void structuredFollowUpMakesTheSessionReadyAndKeepsOptionalErrorCodeUnknown() {
        IntakeSession awaiting = reducer.start(
                "intake-1",
                envelope("msg-1", "会话消息发送失败", List.of(), FIRST_MESSAGE_AT));

        IntakeSession ready = reducer.accept(awaiting, envelope(
                "msg-2",
                """
                        系统: CSDP
                        服务: csdp-wechat
                        客户ID: tenant-42
                        发生时间: 2026-07-29 10:05:00
                        """,
                List.of(),
                FIRST_MESSAGE_AT.plusSeconds(30)));

        assertEquals(IntakeSessionStatus.READY, ready.status());
        assertEquals("CSDP", ready.system());
        assertEquals("csdp-wechat", ready.service());
        assertEquals("tenant-42", ready.customerRef());
        assertEquals(Instant.parse("2026-07-29T02:05:00Z"), ready.occurredAt());
        assertEquals(FIRST_MESSAGE_AT.plusSeconds(30), ready.readyAt());
        assertNull(ready.errorCode(), "an omitted error code must remain unknown");
        assertTrue(ready.missingFields().isEmpty());
        String prompt = IntakeDecision.from(ready, false, false).prompt();
        assertTrue(prompt.contains("正在生成排障单"));
        assertTrue(prompt.contains("不会执行任何生产变更"));
    }

    @Test
    void invalidTimeStaysMissingInsteadOfBeingReplacedWithReceiptTime() {
        IntakeSession session = reducer.start("intake-1", envelope(
                "msg-1",
                """
                        现象: 会话消息发送失败
                        系统: CSDP
                        服务: csdp-wechat
                        客户ID: tenant-42
                        发生时间: 刚刚
                        """,
                List.of(),
                FIRST_MESSAGE_AT));

        assertEquals(IntakeSessionStatus.AWAITING_INPUT, session.status());
        assertNull(session.occurredAt());
        assertEquals(List.of("occurredAt"), session.missingFields());
        assertTrue(IntakeDecision.from(session, false, false).prompt()
                .contains("2026-08-12 16:36:00"));
    }

    @Test
    void pastedIcareDialProbeAlertBecomesReadyWithoutManualFieldTranscription() {
        String alert = """
                sf-icare-app-虚机-拨测检测异常
                ■【重要】2026-08-12 16:36:00 (r/95b771)
                业务系统：深信服新ICare系统-邹汶达
                告警分组：ICARE告警
                告警级别：error
                监控项：sf-icare-app-虚机-拨测检测异常，请及时关注处理
                告警URL：http://icarenew.sangfor.com/index.html
                """;
        IntakeSession session = reducer.start(
                "intake-icare",
                envelope("msg-icare", alert, List.of(), FIRST_MESSAGE_AT));

        assertEquals(IntakeSessionStatus.READY, session.status());
        assertEquals("深信服新ICare系统-邹汶达", session.system());
        assertEquals("sf-icare-app", session.service());
        assertEquals("未知", session.customerRef());
        assertEquals(Instant.parse("2026-08-12T08:36:00Z"), session.occurredAt());
        assertTrue(session.symptom().contains("sf-icare-app"));
        assertTrue(session.missingFields().isEmpty());
        assertTrue(IntakeDecision.from(session, false, false).prompt().contains("正在生成排障单"));
    }

    @Test
    void awaitingPromptShowsRecognizedFieldsInsteadOfBlankAsk() {
        IntakeSession session = reducer.start(
                "intake-1",
                envelope(
                        "msg-1",
                        "业务系统：CSDP\n现象: 消息发送失败\n发生时间: 2026-08-12 16:36:00",
                        List.of(),
                        FIRST_MESSAGE_AT));

        assertEquals(IntakeSessionStatus.AWAITING_INPUT, session.status());
        String prompt = IntakeDecision.from(session, false, false).prompt();
        assertTrue(prompt.contains("已从告警中识别"));
        assertTrue(prompt.contains("系统=CSDP"));
        assertTrue(prompt.contains("还需要"));
    }

    @Test
    void attachmentReferenceNeverPersistsLocalPathOrSignedUrl() {
        var part = new vip.mate.workspace.conversation.model.MessageContentPart();
        part.setType("image");
        part.setStoredName("stored-image.png");
        part.setFileName("screen.png");
        part.setPath("/private/tmp/secret/screen.png");
        part.setFileUrl("https://signed.example/screen.png?token=secret");
        part.setMediaId("/private/tmp/secret/screen.png");
        part.setContentType("image/png");
        part.setFileSize(2048L);

        List<IntakeAttachmentRef> refs = IntakeAttachmentRef.fromContentParts(
                List.of(part), "msg-1");

        assertEquals(1, refs.size());
        assertEquals("stored-image.png", refs.getFirst().reference());
        assertFalse(refs.getFirst().reference().contains("/private/"));
        assertFalse(refs.getFirst().reference().contains("token="));
    }

    @Test
    void unsafeAttachmentNamesAndFallbackIdsAreNotPersistedVerbatim() {
        var part = new vip.mate.workspace.conversation.model.MessageContentPart();
        part.setType("file");
        part.setFileName("https://signed.example/private.log?token=secret");
        part.setStoredName("/private/tmp/secret/private.log");
        part.setContentType("https://signed.example/type?token=secret");

        List<IntakeAttachmentRef> refs = IntakeAttachmentRef.fromContentParts(
                List.of(part), "/private/message/token=secret");

        assertEquals(1, refs.size());
        assertNull(refs.getFirst().fileName());
        assertNull(refs.getFirst().contentType());
        assertTrue(refs.getFirst().reference().startsWith("channel-message:"));
        assertFalse(refs.getFirst().reference().contains("/private/"));
        assertFalse(refs.getFirst().reference().contains("token="));
    }

    @Test
    void readyCheckpointCannotBeSilentlyRewrittenByALaterChatFrame() {
        IntakeSession awaiting = reducer.start(
                "intake-1",
                envelope("msg-1", "会话消息发送失败", List.of(), FIRST_MESSAGE_AT));
        IntakeSession ready = reducer.accept(awaiting, envelope(
                "msg-2",
                "系统: CSDP\n服务: csdp-wechat\n客户ID: tenant-42\n发生时间: 2026-07-29 10:05:00",
                List.of(),
                FIRST_MESSAGE_AT.plusSeconds(30)));

        IntakeSession unchanged = reducer.accept(ready, envelope(
                "msg-3",
                "系统: WRONG\n服务: wrong-service",
                List.of(),
                FIRST_MESSAGE_AT.plusSeconds(60)));

        assertEquals(ready, unchanged);
    }

    private IntakeMessageEnvelope envelope(
            String messageId,
            String text,
            List<IntakeAttachmentRef> attachments,
            Instant receivedAt) {
        return new IntakeMessageEnvelope(
                7L,
                "wecom",
                messageId,
                "group-1",
                "user-1",
                text,
                attachments,
                receivedAt);
    }
}
