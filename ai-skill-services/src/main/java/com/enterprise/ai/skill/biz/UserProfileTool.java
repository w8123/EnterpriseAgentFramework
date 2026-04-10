package com.enterprise.ai.skill.biz;

import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用户身份信息查询工具
 * <p>
 * TODO: 当前为 Mock 实现，后续对接业务系统真实接口
 */
@Slf4j
@Component
public class UserProfileTool implements AiTool {

    @Override
    public String name() {
        return "query_user_profile";
    }

    @Override
    public String description() {
        return "查询当前登录用户的身份信息，包括姓名、年龄、性别、身高等基本资料。参数：user_id（用户ID或工号，可为空）。";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(ToolParameter.optional("user_id", "string", "用户ID或工号"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String userId = (String) args.get("user_id");
        log.info("[UserProfileTool] 查询用户身份: userId={}", userId);
        return "{\"name\":\"靳圣辉\",\"age\":25,\"gender\":\"男\",\"height\":\"185cm\"}";
    }
}
