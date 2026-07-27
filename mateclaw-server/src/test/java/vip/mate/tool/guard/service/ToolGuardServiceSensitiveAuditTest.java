package vip.mate.tool.guard.service;

import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.engine.ToolGuardEngine;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolGuardServiceSensitiveAuditTest {

    @Test
    void suppressesAuditButStillEvaluatesTheOriginalArguments() {
        ToolGuardEngine engine = mock(ToolGuardEngine.class);
        ToolGuardAuditService audit = mock(ToolGuardAuditService.class);
        ToolGuardConfigService config = mock(ToolGuardConfigService.class);
        ToolGuardService service = new ToolGuardService(engine, audit, config);
        ToolInvocationContext context = ToolInvocationContext.of(
                "collect_troubleshooting_evidence",
                "{\"token\":\"tool-secret\"}", "triage-1", "agent-1");
        when(config.isEnabled()).thenReturn(true);
        when(config.getDeniedTools()).thenReturn(Set.of());
        when(engine.evaluate(context, true))
                .thenReturn(GuardEvaluation.allow(context.toolName()));

        assertThat(service.evaluate(context, true, true).isAllowed()).isTrue();

        verify(engine).evaluate(context, true);
        verifyNoInteractions(audit);
    }
}
