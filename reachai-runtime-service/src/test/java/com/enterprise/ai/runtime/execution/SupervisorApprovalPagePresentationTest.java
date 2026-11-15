package com.enterprise.ai.runtime.execution;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorApprovalPagePresentationTest {
    @Test
    void approvalMustShowTheExactPinnedBusinessTargetAndAction() {
        Map<String, Object> declaration = Map.of("targetAlias", "客户资料", "actionTitle", "填写客户资料",
                "actionDescription", "填写指定字段", "effect", "PAGE_LOCAL_DRAFT", "fieldLabels", Map.of("name", "客户名称"));
        Map<String, Object> pin = Map.of("declaration", declaration);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pageTarget", "客户资料"); data.put("pageActionTitle", "填写客户资料");
        data.put("pageActionDescription", "填写指定字段"); data.put("pageEffect", "PAGE_LOCAL_DRAFT");
        data.put("arguments", Map.of("name", "小黑"));
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("title", "确认页面动作：填写客户资料");
        ui.put("message", "业务目标：客户资料；页面动作：填写客户资料；说明：填写指定字段；本次仅处理这些字段：客户名称（name）；未列出的内容未处理；业务方声明此动作不会主动提交，但业务页面仍可能自动保存，请核对。操作参数：name=小黑");
        ui.put("data", data);
        assertTrue(SupervisorApprovalPresentation.pageDeclarationComplete(pin, ui));
        data.put("pageTarget", "其他资料");
        assertFalse(SupervisorApprovalPresentation.pageDeclarationComplete(pin, ui));
        assertFalse(SupervisorApprovalPresentation.pageDeclarationComplete(Map.of(), ui));
        data.put("pageTarget", "客户资料");
        var withoutDescription = new LinkedHashMap<String, Object>(declaration);
        withoutDescription.put("actionDescription", null);
        data.put("pageActionDescription", "未提供");
        ui.put("message", "业务目标：客户资料；页面动作：填写客户资料；说明：未提供；本次仅处理这些字段：客户名称（name）；未列出的内容未处理；业务方声明此动作不会主动提交，但业务页面仍可能自动保存，请核对。操作参数：name=小黑");
        assertTrue(SupervisorApprovalPresentation.pageDeclarationComplete(Map.of("declaration", withoutDescription), ui));
    }
}
