package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationIntakeControllerRequestTest {

    @Test
    @DisplayName("Web 对话未声明模式时默认演练，只有显式 false 才创建正式单")
    void defaultsToRehearsal() {
        assertThat(new ConversationIntakeController.ConversationTurnRequest(
                null, "CSDP 消息失败", null).isRehearsal()).isTrue();
        assertThat(new ConversationIntakeController.ConversationTurnRequest(
                null, "CSDP 消息失败", true).isRehearsal()).isTrue();
        assertThat(new ConversationIntakeController.ConversationTurnRequest(
                null, "CSDP 消息失败", false).isRehearsal()).isFalse();
    }
}
