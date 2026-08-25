package vip.mate.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.approval.model.ToolApprovalEntity;
import vip.mate.workspace.conversation.ConversationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalWorkflowServicePersistenceTest {

    @Test
    void createPendingFailsClosedAndRemovesMemoryEntryWhenDatabaseWriteFails() {
        ApprovalService approvalService = new ApprovalService();
        ToolApprovalMapper mapper = mock(ToolApprovalMapper.class);
        when(mapper.insert(any(ToolApprovalEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        ApprovalWorkflowService workflow = new ApprovalWorkflowService(
                approvalService, mapper, new ObjectMapper(), mock(ConversationService.class));

        assertThatThrownBy(() -> workflow.createPending(
                "conv-1", "user-1", "itdb_approve_sql_request", "{}", "confirm",
                "{}", "[]", "agent-1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("persist approval");
        assertThat(approvalService.findPendingByConversation("conv-1")).isNull();
    }
}
