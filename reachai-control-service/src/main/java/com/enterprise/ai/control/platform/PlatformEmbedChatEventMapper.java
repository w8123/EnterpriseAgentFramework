package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlatformEmbedChatEventMapper extends BaseMapper<PlatformEmbedChatEventEntity> {

    /** 每个会话的用户提问数、最近活动时间和最近一条提问，供运行中心对话列表使用。 */
    @Select("""
            <script>
            SELECT s.session_id AS sessionId,
                   s.question_count AS questionCount,
                   s.last_event_at AS lastEventAt,
                   q.content AS lastQuestion
            FROM (
                SELECT e.session_id,
                       SUM(CASE WHEN e.role = 'user' THEN 1 ELSE 0 END) AS question_count,
                       MAX(e.created_at) AS last_event_at,
                       MAX(CASE WHEN e.role = 'user' THEN e.id ELSE NULL END) AS last_question_id
                FROM control_embed_chat_event e
                WHERE e.session_id IN
                <foreach collection="sessionIds" item="sessionId" open="(" separator="," close=")">#{sessionId}</foreach>
                GROUP BY e.session_id
            ) s
            LEFT JOIN control_embed_chat_event q ON q.id = s.last_question_id
            </script>
            """)
    List<PlatformEmbedConversationStats> selectConversationStats(@Param("sessionIds") List<String> sessionIds);
}
