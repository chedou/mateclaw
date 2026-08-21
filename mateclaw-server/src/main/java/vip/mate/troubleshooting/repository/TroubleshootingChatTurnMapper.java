package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingChatTurnEntity;

@Mapper
public interface TroubleshootingChatTurnMapper extends BaseMapper<TroubleshootingChatTurnEntity> {
    @Select("""
            SELECT id, workspace_id, conversation_id, client_turn_id, agent_id,
                   user_message_id, assistant_message_id, deleted, create_time, update_time
              FROM mate_troubleshooting_chat_turn
             WHERE workspace_id = #{workspaceId}
               AND conversation_id = #{conversationId}
               AND client_turn_id = #{clientTurnId}
               AND deleted = 0
             FOR UPDATE
            """)
    TroubleshootingChatTurnEntity findForUpdate(
            @Param("workspaceId") long workspaceId,
            @Param("conversationId") String conversationId,
            @Param("clientTurnId") String clientTurnId);
}
