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
    void pastedCsdpAlertExtractsTheExplicitBracketedErrorCode() {
        String alert = """
                客服数字化(WECHAT)-【ITGW访问失败】-事件
                ■【紧急】2026-08-07 17:12:00 (r/e4d3f5)
                集群：sz3-s-k8s
                服务：csdp-wechat
                数量：6
                异常：ITGW访问失败【904003】
                说明：异常事件
                """;

        IntakeSession session = reducer.start(
                "intake-itgw",
                envelope("msg-itgw", alert, List.of(), FIRST_MESSAGE_AT));

        assertEquals(IntakeSessionStatus.AWAITING_INPUT, session.status());
        assertEquals("ITGW访问失败【904003】", session.symptom());
        assertEquals("csdp-wechat", session.service());
        assertEquals("未知", session.customerRef());
        assertEquals(Instant.parse("2026-08-07T09:12:00Z"), session.occurredAt());
        assertEquals("904003", session.errorCode(),
                "告警已明确给出括号错误码时，应结构化提取而不是进入无码 Agent 路径");
        assertEquals(List.of("system"), session.missingFields(),
                "服务告警缺少系统字段时只应追问系统，不能猜一个错误码路由系统");
    }

    @Test
    void conflictingBracketedErrorCodesAreNotGuessed() {
        IntakeSession session = reducer.start(
                "intake-conflicting-codes",
                envelope(
                        "msg-conflicting-codes",
                        "异常：ITGW访问失败【904003】\n错误：下游返回【701022】",
                        List.of(),
                        FIRST_MESSAGE_AT));

        assertNull(session.errorCode(),
                "同一告警出现多个候选错误码时必须保持未知，等待显式确认");
    }

    @Test
    void bracketedPeopleServicesAndSeverityAreNeverPromotedToAnErrorCode() {
        for (String text : List.of(
                "问题负责人【Alice】",
                "现象：调用服务【csdp-wechat】超时",
                "异常等级【P01】",
                "Error: timeout",
                "现象：接口返回订单号【123456】",
                "异常：下游返回用户ID【123456】")) {
            IntakeSession session = reducer.start(
                    "intake-non-code-" + text.hashCode(),
                    envelope("msg-non-code-" + text.hashCode(), text, List.of(), FIRST_MESSAGE_AT));

            assertNull(session.errorCode(), text + " 不是显式错误码，不能进入确定性路由");
        }
    }

    @Test
    void explicitErrorCodeAliasesRemainSupported() {
        for (String label : List.of("error_code", "error-code", "error code", "错误码")) {
            IntakeSession session = reducer.start(
                    "intake-code-alias-" + label.hashCode(),
                    envelope(
                            "msg-code-alias-" + label.hashCode(),
                            label + ": 903001",
                            List.of(),
                            FIRST_MESSAGE_AT));

            assertEquals("903001", session.errorCode(),
                    label + " 是明确的错误码标签，必须保持兼容");
        }
    }

    @Test
    void explicitErrorCodeAndSymptomCodeConflictRequiresClarification() {
        IntakeSession session = reducer.start(
                "intake-code-conflict",
                envelope(
                        "msg-code-conflict",
                        "error_code：701022\n异常：ITGW访问失败【904003】",
                        List.of(),
                        FIRST_MESSAGE_AT));

        assertNull(session.errorCode(),
                "显式错误码和异常括号码不一致时，不能任取一个作为权威路由");
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
    void followUpPromptShowsAnExtractedErrorCodeInsteadOfHidingTheRouteFact() {
        IntakeSession session = reducer.start(
                "intake-code-visible",
                envelope(
                        "msg-code-visible",
                        "服务：csdp-wechat\n异常：ITGW访问失败【904003】\n"
                                + "发生时间：2026-08-07 17:12:00",
                        List.of(),
                        FIRST_MESSAGE_AT));

        String prompt = IntakeDecision.from(session, false, false).prompt();

        assertTrue(prompt.contains("错误码=904003"),
                "需要补问系统时，也应把已识别的错误码展示给操作员复核");
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
