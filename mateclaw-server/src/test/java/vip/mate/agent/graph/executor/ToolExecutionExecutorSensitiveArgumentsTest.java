package vip.mate.agent.graph.executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.tool.guard.engine.ToolGuardEngine;
import vip.mate.tool.guard.engine.ToolPolicyResolver;
import vip.mate.tool.guard.guardian.WorkspaceBoundaryGuardian;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.service.ToolGuardAuditService;
import vip.mate.tool.guard.service.ToolGuardConfigService;
import vip.mate.tool.guard.service.ToolGuardService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolExecutionExecutorSensitiveArgumentsTest {

    @Test
    void hardScopedArgumentsReachPolicyAndCallbackButNotEventsOrSse() {
        String secret = "tool-secret-must-not-leak";
        String arguments = "{\"requestId\":\"token:" + secret
                + "\",\"targetJson\":\"{\\\"credential\\\":\\\"" + secret
                + "\\\"}\"}";
        AtomicReference<String> callbackArguments = new AtomicReference<>();
        ToolCallback callback = callback(
                "collect_troubleshooting_evidence",
                raw -> {
                    callbackArguments.set(raw);
                    return "{\"status\":\"MISSING\"}";
                });
        ToolGuardService guardService = mock(ToolGuardService.class);
        when(guardService.evaluate(any(ToolInvocationContext.class), eq(true), eq(true)))
                .thenAnswer(invocation -> {
                    ToolInvocationContext context = invocation.getArgument(0);
                    assertThat(context.rawArguments()).contains(secret);
                    return GuardEvaluation.allow(context.toolName());
                });
        ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                guardService, null, streamTracker);
        executor.setSensitiveArgumentSideChannelsSuppressed(true);

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "collect_troubleshooting_evidence", arguments)),
                "triage-1", "agent-1", false, "troubleshooting", null,
                ChatOrigin.web("triage-1", "troubleshooting", 7L, null));

        assertThat(callbackArguments.get()).isEqualTo(arguments);
        assertThat(result.events().toString()).doesNotContain(secret);
        var payloads = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(streamTracker, atLeastOnce())
                .broadcastObject(eq("triage-1"), any(String.class), payloads.capture());
        assertThat(payloads.getAllValues().toString()).doesNotContain(secret);
        verify(guardService).evaluate(any(ToolInvocationContext.class), eq(true), eq(true));
    }

    @Test
    void hardScopedApprovalDecisionIsBlockedWithoutPersistingARequest() {
        String secret = "approval-secret-must-not-leak";
        ToolCallback callback = callback(
                "collect_troubleshooting_evidence", raw -> "should-not-run");
        ToolGuardService guardService = mock(ToolGuardService.class);
        when(guardService.evaluate(any(ToolInvocationContext.class), eq(true), eq(true)))
                .thenReturn(new GuardEvaluation(
                        "collect_troubleshooting_evidence",
                        List.of(),
                        null,
                        GuardDecision.NEEDS_APPROVAL,
                        "approval finding contains " + secret));
        ApprovalWorkflowService approval = mock(ApprovalWorkflowService.class);
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                guardService, approval, null);
        executor.setSensitiveArgumentSideChannelsSuppressed(true);

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "collect_troubleshooting_evidence",
                        "{\"targetJson\":\"" + secret + "\"}")),
                "triage-1", "agent-1", false);

        assertThat(result.awaitingApproval()).isFalse();
        assertThat(result.events().toString()).doesNotContain(secret);
        assertThat(result.responses().toString()).doesNotContain(secret);
        verifyNoInteractions(approval);
    }

    @Test
    void hardScopedCallbackFailureDoesNotExposeExceptionDetails() {
        String secret = "exception-secret-must-not-leak";
        ToolCallback callback = callback(
                "collect_troubleshooting_evidence",
                raw -> { throw new IllegalStateException(secret); });
        ToolGuardService guardService = mock(ToolGuardService.class);
        when(guardService.evaluate(any(ToolInvocationContext.class), eq(true), eq(true)))
                .thenAnswer(invocation -> GuardEvaluation.allow(
                        invocation.<ToolInvocationContext>getArgument(0).toolName()));
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                guardService, null, null);
        executor.setSensitiveArgumentSideChannelsSuppressed(true);

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "collect_troubleshooting_evidence", "{}")),
                "triage-1", "agent-1", false);

        assertThat(result.events().toString()).doesNotContain(secret);
        assertThat(result.responses().toString()).doesNotContain(secret);
    }

    @Test
    void hardScopedUnboundFileToolDoesNotLogRawPathInsideGuardian() {
        String secret = "guardian-path-secret-must-not-leak";
        String rawPath = "/outside/" + secret;
        ChatUploadLocationResolver resolver = mock(ChatUploadLocationResolver.class);
        when(resolver.resolveCandidateUploadRoots("triage-1"))
                .thenThrow(new IllegalStateException("resolver failed for " + secret));
        WorkspaceBoundaryGuardian guardian = new WorkspaceBoundaryGuardian(resolver);
        ToolGuardEngine engine = new ToolGuardEngine(
                List.of(guardian), new ToolPolicyResolver());
        ToolGuardAuditService audit = mock(ToolGuardAuditService.class);
        ToolGuardConfigService config = mock(ToolGuardConfigService.class);
        when(config.isEnabled()).thenReturn(true);
        when(config.getDeniedTools()).thenReturn(Set.of());
        ToolGuardService guardService = new ToolGuardService(engine, audit, config);
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                AgentToolSet.fromCallbacks(List.of(), List.of()),
                guardService, null, null);
        executor.setSensitiveArgumentSideChannelsSuppressed(true);

        Logger logger = (Logger) LoggerFactory.getLogger(WorkspaceBoundaryGuardian.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        WorkspacePathGuard.setDefaultRoot("/tmp/mateclaw-hard-scope-guard-root");

        try {
            ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                    List.of(new AssistantMessage.ToolCall(
                            "call-1", "function", "read_file",
                            "{\"filePath\":\"" + rawPath + "\"}")),
                    "triage-1", "agent-1", false);
            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertThat(logs).doesNotContain(secret, rawPath);
            assertThat(result.events().toString()).doesNotContain(secret);
            assertThat(result.responses().toString()).doesNotContain(secret);
        } finally {
            WorkspacePathGuard.setDefaultRoot(null);
            logger.setLevel(previousLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static ToolCallback callback(
            String name,
            java.util.function.Function<String, String> handler) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test tool")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(false).build();
            }

            @Override
            public String call(String raw) {
                return handler.apply(raw);
            }

            @Override
            public String call(String raw, ToolContext context) {
                return handler.apply(raw);
            }
        };
    }
}
