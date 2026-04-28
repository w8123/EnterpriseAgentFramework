package com.enterprise.ai.agent.skill.slot.extractor;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 槽位提取的上下文。给提取器侧通道信息（用户身份、当前时间、Skill / 业务变量），
 * 让"上周三 / 给隔壁部门同事 / 给我自己"这种相对引用有的可解。
 *
 * @param userId       发起调用的业务 userId（可空）
 * @param userDeptId   userId 当前所属部门 ID，用于人员同名消歧（可空）
 * @param traceId      关联 {@code tool_call_log.trace_id}
 * @param now          时间表达式基准（默认 {@link LocalDateTime#now()}）
 * @param sessionVars  Skill 自身的 sessionVars（通常是已经被填的其它 slot），允许提取器跨字段联动
 */
@Builder
public record ExtractContext(
        String userId,
        String userDeptId,
        String traceId,
        LocalDateTime now,
        Map<String, Object> sessionVars
) {

    /** 测试场景的便捷工厂。 */
    public static ExtractContext anonymous() {
        return ExtractContext.builder()
                .now(LocalDateTime.now())
                .sessionVars(Map.of())
                .build();
    }
}
