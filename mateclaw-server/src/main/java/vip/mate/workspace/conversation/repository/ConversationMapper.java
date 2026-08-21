package vip.mate.workspace.conversation.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.workspace.conversation.model.ConversationEntity;

/**
 * 会话 Mapper
 *
 * @author MateClaw Team
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Select("SELECT id FROM mate_conversation WHERE conversation_id = #{conversationId} FOR UPDATE")
    Long lockId(@Param("conversationId") String conversationId);

    @Select("SELECT * FROM mate_conversation WHERE conversation_id = #{conversationId} FOR UPDATE")
    ConversationEntity lockByConversationId(@Param("conversationId") String conversationId);
}
