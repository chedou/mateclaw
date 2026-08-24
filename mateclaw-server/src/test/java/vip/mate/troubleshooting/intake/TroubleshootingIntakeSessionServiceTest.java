package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeMessageReceiptMapper;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeInvestigationMapper;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeSessionMapper;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TroubleshootingIntakeSessionServiceTest {

    @Mock private TroubleshootingIntakeSessionMapper sessionMapper;
    @Mock private TroubleshootingIntakeMessageReceiptMapper receiptMapper;
    @Mock private TroubleshootingIntakeInvestigationMapper investigationMapper;
    @Mock private TroubleshootingSopPersistenceService sopPersistence;

    private ObjectMapper objectMapper;
    private TroubleshootingIntakeSessionService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                TroubleshootingIntakeSessionEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                TroubleshootingIntakeMessageReceiptEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                TroubleshootingIntakeInvestigationEntity.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TroubleshootingIntakeSessionService(
                sessionMapper,
                receiptMapper,
                investigationMapper,
                objectMapper,
                new IntakeSessionReducer());
    }

    @Test
    void firstMessagePersistsRedactedAggregateAndContentFreeReceipt() {
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.accept(envelope(
                "msg-1", "会话消息发送失败 token=super-secret", at(10, 0)));

        assertEquals(IntakeSessionStatus.AWAITING_INPUT, decision.status());
        ArgumentCaptor<TroubleshootingIntakeSessionEntity> session =
                ArgumentCaptor.forClass(TroubleshootingIntakeSessionEntity.class);
        verify(sessionMapper).insert(session.capture());
        assertEquals(64, session.getValue().getActiveKey().length());
        assertEquals(session.getValue().getActiveKey(), session.getValue().getRoutingKey());
        assertFalse(session.getValue().getActiveKey().contains("group-1"));
        assertEquals("wecom:99:group-1", session.getValue().getDeliveryConversationId());
        assertFalse(session.getValue().getAggregateJson().contains("super-secret"));

        ArgumentCaptor<TroubleshootingIntakeMessageReceiptEntity> receipt =
                ArgumentCaptor.forClass(TroubleshootingIntakeMessageReceiptEntity.class);
        verify(receiptMapper).insert(receipt.capture());
        assertEquals("msg-1", receipt.getValue().getSourceMessageId());
        assertEquals(session.getValue().getIntakeSessionId(),
                receipt.getValue().getIntakeSessionId());
    }

    @Test
    void firstWebConversationMessageFreezesTheRequestedModeInTheAggregate() throws Exception {
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.acceptConversation(
                webEnvelope("msg-formal-1", "会话创建失败", at(10, 0)), false);

        ArgumentCaptor<TroubleshootingIntakeSessionEntity> inserted =
                ArgumentCaptor.forClass(TroubleshootingIntakeSessionEntity.class);
        verify(sessionMapper).insert(inserted.capture());
        IntakeSession stored = objectMapper.readValue(
                inserted.getValue().getAggregateJson(), IntakeSession.class);
        assertEquals(false, stored.rehearsal());
        assertEquals(stored.intakeSessionId(), decision.intakeSessionId());
    }

    @Test
    void laterWebConversationTurnCannotChangeTheFrozenMode() throws Exception {
        IntakeSession formal = new IntakeSessionReducer().start(
                "intake-formal",
                webEnvelope("msg-formal-1", "会话创建失败", at(10, 0)),
                false);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(entity(formal, 2));

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.acceptConversation(
                        webEnvelope("msg-formal-2", "系统: CSDP", at(10, 1)), true));

        assertEquals(409, error.getCode());
        assertEquals("err.troubleshooting.conversation_mode_conflict", error.getMsgKey());
        verify(receiptMapper, never()).insert(
                any(TroubleshootingIntakeMessageReceiptEntity.class));
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void omittedModeOnLaterTurnReusesTheFrozenServerMode() throws Exception {
        IntakeSession formal = new IntakeSessionReducer().start(
                "intake-formal",
                webEnvelope("msg-formal-1", "会话创建失败", at(10, 0)),
                false);
        TroubleshootingIntakeSessionEntity entity = entity(formal, 2);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(entity);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        IntakeDecision decision = service.acceptConversation(
                webEnvelope("msg-formal-2", "系统: CSDP", at(10, 1)), null);

        assertEquals("intake-formal", decision.intakeSessionId());
        assertEquals(false, service.getConversationMode(
                7L, "conv-web-1", "user-1").rehearsal());
    }

    @Test
    void explicitChoiceBindsALegacyWebAggregateThatPredatesTheModeField() throws Exception {
        IntakeSession legacy = new IntakeSessionReducer().start(
                "intake-legacy",
                webEnvelope("msg-legacy-1", "会话创建失败", at(10, 0)));
        TroubleshootingIntakeSessionEntity legacyEntity = entity(legacy, 2);
        com.fasterxml.jackson.databind.node.ObjectNode oldJson =
                (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(
                        legacyEntity.getAggregateJson());
        oldJson.remove("rehearsal");
        legacyEntity.setAggregateJson(objectMapper.writeValueAsString(oldJson));
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(legacyEntity);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        IntakeDecision decision = service.acceptConversation(
                webEnvelope("msg-legacy-2", "系统: CSDP", at(10, 0)), false);

        assertEquals("intake-legacy", decision.intakeSessionId());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessionMapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(value -> value instanceof String text
                        && text.contains("\"rehearsal\":false")));
    }

    @Test
    void exactApprovedRouteCompletesAPastedAlertWithoutGuessingTheSystem() throws Exception {
        service = new TroubleshootingIntakeSessionService(
                sessionMapper,
                receiptMapper,
                investigationMapper,
                objectMapper,
                new IntakeSessionReducer(),
                sopPersistence);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);
        when(investigationMapper.insert(any(TroubleshootingIntakeInvestigationEntity.class)))
                .thenReturn(1);
        when(sopPersistence.findUniqueOperationalSystem(7L, "csdp-wechat", "904003"))
                .thenReturn(java.util.Optional.of("CSDP"));

        IntakeDecision decision = service.accept(envelope(
                "msg-itgw",
                """
                客服数字化(WECHAT)-【ITGW访问失败】-事件
                ■【紧急】2026-08-07 17:12:00 (r/e4d3f5)
                集群：sz3-s-k8s
                服务：csdp-wechat
                数量：6
                异常：ITGW访问失败【904003】
                说明：异常事件
                """,
                at(10, 0)));

        assertEquals(IntakeSessionStatus.READY, decision.status());
        ArgumentCaptor<TroubleshootingIntakeSessionEntity> stored =
                ArgumentCaptor.forClass(TroubleshootingIntakeSessionEntity.class);
        verify(sessionMapper).insert(stored.capture());
        IntakeSession session = objectMapper.readValue(
                stored.getValue().getAggregateJson(), IntakeSession.class);
        assertEquals("CSDP", session.system());
        assertEquals("csdp-wechat", session.service());
        assertEquals("904003", session.errorCode());
        assertTrue(session.missingFields().isEmpty());
        verify(investigationMapper).insert(
                any(TroubleshootingIntakeInvestigationEntity.class));
    }

    @Test
    void missingOrAmbiguousRouteStaysInFollowUpAndNeverStartsInvestigation() {
        service = new TroubleshootingIntakeSessionService(
                sessionMapper,
                receiptMapper,
                investigationMapper,
                objectMapper,
                new IntakeSessionReducer(),
                sopPersistence);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);
        when(sopPersistence.findUniqueOperationalSystem(7L, "csdp-wechat", "904003"))
                .thenReturn(java.util.Optional.empty());

        IntakeDecision decision = service.accept(envelope(
                "msg-itgw-no-route",
                "服务：csdp-wechat\n异常：ITGW访问失败【904003】\n"
                        + "发生时间：2026-08-07 17:12:00",
                at(10, 0)));

        assertEquals(IntakeSessionStatus.AWAITING_INPUT, decision.status());
        assertEquals(List.of("system"), decision.missingFields());
        assertTrue(decision.prompt().contains("错误码=904003"));
        verify(investigationMapper, never()).insert(
                any(TroubleshootingIntakeInvestigationEntity.class));
    }

    @Test
    void duplicateWebhookReturnsStoredDecisionWithoutApplyingAgain() throws Exception {
        IntakeSession stored = new IntakeSessionReducer().start(
                "intake-1", envelope("msg-1", "会话消息发送失败", at(10, 0)));
        TroubleshootingIntakeMessageReceiptEntity receipt = new TroubleshootingIntakeMessageReceiptEntity();
        receipt.setIntakeSessionId("intake-1");
        when(receiptMapper.selectOne(any())).thenReturn(receipt);
        when(sessionMapper.selectOne(any())).thenReturn(entity(stored, 2));

        IntakeDecision duplicate = service.accept(
                envelope("msg-1", "会话消息发送失败", at(10, 0)));

        assertTrue(duplicate.duplicate());
        assertEquals("intake-1", duplicate.intakeSessionId());
        verify(receiptMapper, never()).insert(
                any(TroubleshootingIntakeMessageReceiptEntity.class));
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void duplicateReceiptCannotCrossConversationOrReporterBoundary() throws Exception {
        IntakeSession stored = new IntakeSessionReducer().start(
                "intake-user-1",
                webEnvelope("web-msg-shared", "会话消息发送失败", at(10, 0)),
                false);
        TroubleshootingIntakeMessageReceiptEntity receipt =
                new TroubleshootingIntakeMessageReceiptEntity();
        receipt.setIntakeSessionId("intake-user-1");
        when(receiptMapper.selectOne(any())).thenReturn(receipt);
        when(sessionMapper.selectOne(any())).thenReturn(entity(stored, 2));

        IntakeMessageEnvelope otherReporter = new IntakeMessageEnvelope(
                7L,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                "web-msg-shared",
                "conv-web-2",
                "web-conversation:conv-web-2",
                "user-2",
                "会话消息发送失败",
                List.of(),
                at(10, 0));

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.acceptConversation(otherReporter, false));

        assertEquals(409, error.getCode());
        assertEquals("err.troubleshooting.message_identity_conflict", error.getMsgKey());
        verify(receiptMapper, never()).insert(
                any(TroubleshootingIntakeMessageReceiptEntity.class));
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void matchingLegacyWebReceiptIsReusedReadOnlyAfterScopedReceiptMisses() throws Exception {
        String scopedMessageId = "web-msg-" + "a".repeat(64);
        String legacyMessageId = "web-msg-turn-legacy-0001";
        IntakeSession stored = new IntakeSessionReducer().start(
                "intake-legacy-turn",
                webEnvelope(legacyMessageId, "会话创建失败", at(10, 0)),
                false);
        TroubleshootingIntakeMessageReceiptEntity receipt =
                new TroubleshootingIntakeMessageReceiptEntity();
        receipt.setIntakeSessionId("intake-legacy-turn");
        when(receiptMapper.selectOne(any())).thenReturn(null, receipt);
        when(sessionMapper.selectOne(any())).thenReturn(entity(stored, 2));

        IntakeDecision duplicate = service.acceptConversation(
                webEnvelope(scopedMessageId, "会话创建失败", at(10, 0)),
                false,
                legacyMessageId);

        assertTrue(duplicate.duplicate());
        assertEquals("intake-legacy-turn", duplicate.intakeSessionId());
        verify(receiptMapper, times(2)).selectOne(any());
        verify(receiptMapper, never()).insert(
                any(TroubleshootingIntakeMessageReceiptEntity.class));
        verify(sessionMapper, never()).insert(
                any(TroubleshootingIntakeSessionEntity.class));
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void legacyWebReceiptOwnedByAnotherConversationCannotCaptureTheScopedTurn()
            throws Exception {
        String scopedMessageId = "web-msg-" + "b".repeat(64);
        String legacyMessageId = "web-msg-turn-shared-0001";
        IntakeSession otherOwner = new IntakeSessionReducer().start(
                "intake-other-owner",
                webEnvelope(legacyMessageId, "会话创建失败", at(10, 0)),
                false);
        TroubleshootingIntakeMessageReceiptEntity legacyReceipt =
                new TroubleshootingIntakeMessageReceiptEntity();
        legacyReceipt.setIntakeSessionId("intake-other-owner");
        when(receiptMapper.selectOne(any())).thenReturn(null, legacyReceipt);
        when(sessionMapper.selectOne(any()))
                .thenReturn(entity(otherOwner, 2))
                .thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);
        IntakeMessageEnvelope incoming = new IntakeMessageEnvelope(
                7L,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                scopedMessageId,
                "conv-web-2",
                "web:conversation:conv-web-2",
                "user-2",
                "会话创建失败",
                List.of(),
                at(10, 0));

        IntakeDecision created = service.acceptConversation(
                incoming, false, legacyMessageId);

        assertFalse(created.duplicate());
        assertFalse("intake-other-owner".equals(created.intakeSessionId()));
        ArgumentCaptor<TroubleshootingIntakeMessageReceiptEntity> insertedReceipt =
                ArgumentCaptor.forClass(TroubleshootingIntakeMessageReceiptEntity.class);
        verify(receiptMapper).insert(insertedReceipt.capture());
        assertEquals(scopedMessageId, insertedReceipt.getValue().getSourceMessageId());
        assertFalse(legacyMessageId.equals(
                insertedReceipt.getValue().getSourceMessageId()));
    }

    @Test
    void scopedWebReceiptWinsWithoutConsultingTheLegacyAlias() throws Exception {
        String scopedMessageId = "web-msg-" + "c".repeat(64);
        IntakeSession stored = new IntakeSessionReducer().start(
                "intake-scoped-owner",
                webEnvelope(scopedMessageId, "会话创建失败", at(10, 0)),
                false);
        TroubleshootingIntakeMessageReceiptEntity scopedReceipt =
                new TroubleshootingIntakeMessageReceiptEntity();
        scopedReceipt.setIntakeSessionId("intake-scoped-owner");
        when(receiptMapper.selectOne(any())).thenReturn(scopedReceipt);
        when(sessionMapper.selectOne(any())).thenReturn(entity(stored, 2));

        IntakeDecision duplicate = service.acceptConversation(
                webEnvelope(scopedMessageId, "会话创建失败", at(10, 0)),
                false,
                "web-msg-turn-legacy-unused");

        assertTrue(duplicate.duplicate());
        assertEquals("intake-scoped-owner", duplicate.intakeSessionId());
        verify(receiptMapper, times(1)).selectOne(any());
        verify(receiptMapper, never()).insert(
                any(TroubleshootingIntakeMessageReceiptEntity.class));
    }

    @Test
    void malformedLegacyLookupAliasIsIgnoredWithoutRejectingTheScopedTurn() {
        String scopedMessageId = "web-msg-" + "d".repeat(64);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);

        IntakeDecision created = service.acceptConversation(
                webEnvelope(scopedMessageId, "会话创建失败", at(10, 0)),
                false,
                "web-msg-legacy/turn.1");

        assertFalse(created.duplicate());
        ArgumentCaptor<TroubleshootingIntakeMessageReceiptEntity> insertedReceipt =
                ArgumentCaptor.forClass(TroubleshootingIntakeMessageReceiptEntity.class);
        verify(receiptMapper).insert(insertedReceipt.capture());
        assertEquals(scopedMessageId, insertedReceipt.getValue().getSourceMessageId());
        verify(receiptMapper, times(1)).selectOne(any());
    }

    @Test
    void lateOlderMessageAfterReadyReleaseIsReceiptedAgainstPreviousSession() throws Exception {
        IntakeSession current = readyAt("intake-1", at(10, 5));
        TroubleshootingIntakeSessionEntity ready = entity(current, 3);
        ready.setActiveKey(null);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(ready);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.accept(envelope(
                "msg-old",
                "系统: WRONG\n服务: wrong-service",
                at(10, 4)));

        assertTrue(decision.outOfOrder());
        assertEquals(IntakeSessionStatus.READY, decision.status());
        ArgumentCaptor<TroubleshootingIntakeMessageReceiptEntity> receipt =
                ArgumentCaptor.forClass(TroubleshootingIntakeMessageReceiptEntity.class);
        verify(receiptMapper).insert(receipt.capture());
        assertEquals("intake-1", receipt.getValue().getIntakeSessionId());
        verify(sessionMapper, never()).insert(any(TroubleshootingIntakeSessionEntity.class));
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void laterMessageAfterReadyReleaseStartsANewSession() throws Exception {
        IntakeSession previous = readyAt("intake-1", at(10, 5));
        TroubleshootingIntakeSessionEntity ready = entity(previous, 3);
        ready.setActiveKey(null);
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(ready);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.accept(envelope(
                "msg-new",
                "现象: 新的会话消息发送失败",
                at(10, 6)));

        assertFalse(decision.outOfOrder());
        assertEquals(IntakeSessionStatus.AWAITING_INPUT, decision.status());
        assertFalse("intake-1".equals(decision.intakeSessionId()));
        ArgumentCaptor<TroubleshootingIntakeSessionEntity> inserted =
                ArgumentCaptor.forClass(TroubleshootingIntakeSessionEntity.class);
        verify(sessionMapper).insert(inserted.capture());
        assertEquals(64, inserted.getValue().getActiveKey().length());
        assertEquals(inserted.getValue().getActiveKey(), inserted.getValue().getRoutingKey());
    }

    @Test
    void lateMessageForReadySessionIsNotClaimedByTheNewActiveSession() throws Exception {
        IntakeSession previous = readyAt("intake-a", at(10, 5));
        TroubleshootingIntakeSessionEntity readyA = entity(previous, 3);
        readyA.setActiveKey(null);

        IntakeSession active = new IntakeSessionReducer().start(
                "intake-b",
                envelope("msg-b-1", "新的会话消息发送失败", at(10, 10)));
        TroubleshootingIntakeSessionEntity activeB = entity(active, 0);

        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(activeB, readyA);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.accept(envelope(
                "msg-a-late",
                "系统: WRONG\n服务: wrong-service",
                at(10, 4)));

        assertTrue(decision.outOfOrder());
        assertEquals("intake-a", decision.intakeSessionId());
        ArgumentCaptor<TroubleshootingIntakeMessageReceiptEntity> receipt =
                ArgumentCaptor.forClass(TroubleshootingIntakeMessageReceiptEntity.class);
        verify(receiptMapper).insert(receipt.capture());
        assertEquals("intake-a", receipt.getValue().getIntakeSessionId());
        verify(sessionMapper, never()).insert(any(TroubleshootingIntakeSessionEntity.class));
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void equalTimestampCannotOverwriteAnActiveSession() throws Exception {
        IntakeSession active = new IntakeSessionReducer().start(
                "intake-b",
                envelope("msg-b-1", "新的会话消息发送失败", at(10, 10)));
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(entity(active, 0));
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.accept(envelope(
                "msg-b-same-time",
                "系统: WRONG\n服务: wrong-service",
                at(10, 10)));

        assertTrue(decision.outOfOrder());
        assertEquals("intake-b", decision.intakeSessionId());
        verify(sessionMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void optimisticConflictFailsClosedWith409() throws Exception {
        IntakeSession awaiting = new IntakeSessionReducer().start(
                "intake-1", envelope("msg-1", "会话消息发送失败", at(10, 0)));
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(entity(awaiting, 3));
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> service.accept(envelope(
                        "msg-2",
                        "系统: CSDP\n服务: csdp-wechat\n客户ID: tenant-42\n发生时间: 2026-07-29 10:05:00",
                        at(10, 5))));

        assertEquals(409, error.getCode());
    }

    @Test
    void uniqueSourceMessageRaceRetriesAfterRollbackAndReturnsTheWinner() throws Exception {
        IntakeSession stored = new IntakeSessionReducer().start(
                "intake-winner", envelope("msg-1", "会话消息发送失败", at(10, 0)));
        TroubleshootingIntakeMessageReceiptEntity winnerReceipt =
                new TroubleshootingIntakeMessageReceiptEntity();
        winnerReceipt.setIntakeSessionId("intake-winner");
        when(receiptMapper.selectOne(any())).thenReturn(null, winnerReceipt);
        when(sessionMapper.selectOne(any())).thenReturn(entity(stored, 0));
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenThrow(new DuplicateKeyException("concurrent winner"));

        IntakeDecision decision = service.accept(
                envelope("msg-1", "会话消息发送失败", at(10, 0)));

        assertTrue(decision.duplicate());
        assertEquals("intake-winner", decision.intakeSessionId());
        verify(sessionMapper, never()).insert(any(TroubleshootingIntakeSessionEntity.class));
    }

    @Test
    void aReadyFirstMessageDoesNotOccupyTheActiveConversationKey() {
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.insert(any(TroubleshootingIntakeSessionEntity.class)))
                .thenReturn(1);
        when(investigationMapper.insert(any(TroubleshootingIntakeInvestigationEntity.class)))
                .thenReturn(1);

        IntakeDecision decision = service.accept(envelope(
                "msg-complete",
                "现象: 会话消息发送失败\n系统: CSDP\n服务: csdp-wechat\n客户ID: tenant-42\n发生时间: 2026-07-29 10:00:00",
                at(10, 0)));

        assertEquals(IntakeSessionStatus.READY, decision.status());
        ArgumentCaptor<TroubleshootingIntakeSessionEntity> inserted =
                ArgumentCaptor.forClass(TroubleshootingIntakeSessionEntity.class);
        verify(sessionMapper).insert(inserted.capture());
        assertEquals(null, inserted.getValue().getActiveKey());
        ArgumentCaptor<TroubleshootingIntakeInvestigationEntity> investigation =
                ArgumentCaptor.forClass(TroubleshootingIntakeInvestigationEntity.class);
        verify(investigationMapper).insert(investigation.capture());
        assertEquals(decision.intakeSessionId(), investigation.getValue().getIntakeSessionId());
        assertEquals(IntakeInvestigationStatus.PENDING, investigation.getValue().getStatus());
        assertEquals(0, investigation.getValue().getAttempts());
    }

    @Test
    void transitionToReadyAtomicallyReleasesTheActiveConversationKey() throws Exception {
        IntakeSession awaiting = new IntakeSessionReducer().start(
                "intake-1", envelope("msg-1", "会话消息发送失败", at(10, 0)));
        when(receiptMapper.selectOne(any())).thenReturn(null);
        when(sessionMapper.selectOne(any())).thenReturn(entity(awaiting, 3));
        when(receiptMapper.insert(any(TroubleshootingIntakeMessageReceiptEntity.class)))
                .thenReturn(1);
        when(sessionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(investigationMapper.insert(any(TroubleshootingIntakeInvestigationEntity.class)))
                .thenReturn(1);

        service.accept(envelope(
                "msg-2",
                "系统: CSDP\n服务: csdp-wechat\n客户ID: tenant-42\n发生时间: 2026-07-29 10:05:00",
                at(10, 5)));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaUpdateWrapper> update =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessionMapper).update(isNull(), update.capture());
        assertTrue(update.getValue().getSqlSet().contains("active_key"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(null));
        verify(investigationMapper).insert(any(TroubleshootingIntakeInvestigationEntity.class));
    }

    @Test
    void returnsTheTransportRouteSeparatelyFromTheBusinessConversationIdentity()
            throws Exception {
        IntakeSession ready = readyAt("intake-1", at(10, 5));
        TroubleshootingIntakeSessionEntity entity = entity(ready, 3);
        entity.setDeliveryConversationId("wecom:99:group-1");
        when(sessionMapper.selectOne(any())).thenReturn(entity);

        ReadyIntakeDispatch dispatch = service.getReadyDispatch(7L, "intake-1");

        assertEquals("group-1", dispatch.session().conversationRef());
        assertEquals("wecom:99:group-1", dispatch.deliveryConversationId());
    }

    @Test
    void reconciliationBackfillsATaskForHistoricalReadySessions() throws Exception {
        TroubleshootingIntakeSessionEntity ready = entity(
                readyAt("intake-old", at(10, 5)), 3);
        ready.setActiveKey(null);
        when(sessionMapper.selectList(any())).thenReturn(List.of(ready));
        when(investigationMapper.selectCount(any())).thenReturn(0L);
        when(investigationMapper.insert(any(TroubleshootingIntakeInvestigationEntity.class)))
                .thenReturn(1);

        int created = service.reconcileReadyInvestigations();

        assertEquals(1, created);
        ArgumentCaptor<TroubleshootingIntakeInvestigationEntity> task =
                ArgumentCaptor.forClass(TroubleshootingIntakeInvestigationEntity.class);
        verify(investigationMapper).insert(task.capture());
        assertEquals("intake-old", task.getValue().getIntakeSessionId());
        assertEquals(0, task.getValue().getTerminalAttempts());
    }

    @Test
    void reconciliationDoesNotDuplicateAnExistingTask() throws Exception {
        TroubleshootingIntakeSessionEntity ready = entity(
                readyAt("intake-old", at(10, 5)), 3);
        ready.setActiveKey(null);
        when(sessionMapper.selectList(any())).thenReturn(List.of(ready));
        when(investigationMapper.selectCount(any())).thenReturn(1L);

        assertEquals(0, service.reconcileReadyInvestigations());

        verify(investigationMapper, never()).insert(
                any(TroubleshootingIntakeInvestigationEntity.class));
    }

    @Test
    void reconciliationTreatsAConcurrentUniqueWinnerAsSuccess() throws Exception {
        TroubleshootingIntakeSessionEntity ready = entity(
                readyAt("intake-old", at(10, 5)), 3);
        ready.setActiveKey(null);
        when(sessionMapper.selectList(any())).thenReturn(List.of(ready));
        when(investigationMapper.selectCount(any())).thenReturn(0L);
        when(investigationMapper.insert(any(TroubleshootingIntakeInvestigationEntity.class)))
                .thenThrow(new DuplicateKeyException("another node inserted it"));

        assertEquals(0, service.reconcileReadyInvestigations());
    }

    private IntakeSession readyAt(String sessionId, Instant messageAt) {
        IntakeSession awaiting = new IntakeSessionReducer().start(
                sessionId, envelope("msg-1", "会话消息发送失败", at(10, 0)));
        return new IntakeSessionReducer().accept(awaiting, envelope(
                "msg-2",
                "系统: CSDP\n服务: csdp-wechat\n客户ID: tenant-42\n发生时间: 2026-07-29 10:05:00",
                messageAt));
    }

    private TroubleshootingIntakeSessionEntity entity(IntakeSession session, int version)
            throws Exception {
        TroubleshootingIntakeSessionEntity entity = new TroubleshootingIntakeSessionEntity();
        entity.setId(10L);
        entity.setWorkspaceId(session.workspaceId());
        entity.setIntakeSessionId(session.intakeSessionId());
        entity.setActiveKey("a".repeat(64));
        entity.setRoutingKey("a".repeat(64));
        entity.setReportedAt(toLocal(session.reportedAt()));
        entity.setLastMessageAt(toLocal(session.lastMessageAt()));
        entity.setStatus(session.status().name());
        entity.setAggregateJson(objectMapper.writeValueAsString(session));
        entity.setVersion(version);
        return entity;
    }

    private IntakeMessageEnvelope envelope(String messageId, String text, Instant at) {
        return new IntakeMessageEnvelope(
                7L,
                "wecom",
                messageId,
                "group-1",
                "wecom:99:group-1",
                "user-1",
                text,
                List.of(),
                at);
    }

    private IntakeMessageEnvelope webEnvelope(String messageId, String text, Instant at) {
        return new IntakeMessageEnvelope(
                7L,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                messageId,
                "conv-web-1",
                "web:conversation:conv-web-1",
                "user-1",
                text,
                List.of(),
                at);
    }

    private Instant at(int hour, int minute) {
        return LocalDateTime.of(2026, 7, 29, hour, minute)
                .toInstant(ZoneOffset.ofHours(8));
    }

    private LocalDateTime toLocal(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
