package vip.mate.tool.guard.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import vip.mate.tool.guard.guardian.ToolGuardGuardian;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGuardEngineSensitiveLoggingTest {

    @Test
    void sensitiveEvaluationHidesGuardianExceptionMessage() {
        String secret = "guard-secret-must-not-leak";
        ToolGuardGuardian guardian = new ToolGuardGuardian() {
            @Override
            public boolean supports(ToolInvocationContext context) {
                return true;
            }

            @Override
            public boolean alwaysRun() {
                return true;
            }

            @Override
            public List<GuardFinding> evaluate(ToolInvocationContext context) {
                throw new IllegalArgumentException("Invalid path: " + secret);
            }
        };
        ToolGuardEngine engine = new ToolGuardEngine(List.of(guardian), new ToolPolicyResolver());
        Logger logger = (Logger) LoggerFactory.getLogger(ToolGuardEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            ToolInvocationContext context = ToolInvocationContext.of(
                    "collect_troubleshooting_evidence",
                    "{\"purpose\":\"" + secret + "\"}",
                    "triage-1",
                    "agent-1");

            assertThat(engine.evaluate(context, true).isAllowed()).isTrue();
            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertThat(logs)
                    .contains("IllegalArgumentException")
                    .doesNotContain(secret);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
