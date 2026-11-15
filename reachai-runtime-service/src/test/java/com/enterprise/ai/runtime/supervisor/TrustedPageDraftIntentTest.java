package com.enterprise.ai.runtime.supervisor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrustedPageDraftIntentTest {
    @Test
    void reportsOnlyLiteralUnmappedItemLabelsWithoutValues() {
        var pin = Map.<String, Object>of("declaration", Map.of("fieldLabels", Map.of("nickname", "昵称", "address", "地址")));
        var args = Map.<String, Object>of("nickname", "小蓝", "address", "示例路1号");
        assertEquals(List.of("电话"), TrustedPageDraftIntent.unhandledItemLabels(
                "帮我修改个人资料，昵称 小蓝 地址 示例路1号 电话13812345678", args, pin));
        assertEquals(List.of("电话"), TrustedPageDraftIntent.unhandledItemLabels(
                "昵称 小蓝，地址 示例路1号，电话 13812345678", args, pin));
        assertEquals(List.of("邮箱", "电话"), TrustedPageDraftIntent.unhandledItemLabels(
                "昵称：小蓝，地址：示例路1号\n邮箱: demo@example.invalid\n电话13812345678", args, pin));
        assertEquals(List.of(), TrustedPageDraftIntent.unhandledItemLabels(
                "昵称 小蓝，地址 示例路1号，其他的内容帮我也改一下", args, pin));
        assertEquals(List.of(), TrustedPageDraftIntent.unhandledItemLabels(
                "昵称 小蓝，地址 示例路1号，请修改电话13812345678", args, pin));
        assertEquals(List.of(), TrustedPageDraftIntent.unhandledItemLabels(
                "昵称 小蓝，地址 示例路1号，<img src=x>:abc", args, pin));
        assertEquals(List.of(), TrustedPageDraftIntent.unhandledItemLabels(
                "昵称 小蓝，地址 示例路1号，电话\n13812345678", args, pin));
    }

    @Test
    void recognizesOnlyAffirmativeChangesToAnApprovedBusinessTarget() {
        assertTrue(TrustedPageDraftIntent.matches("帮我改一下个人信息，昵称小黑", List.of("个人信息")));
        assertTrue(TrustedPageDraftIntent.matches("请更新客户资料的电话", List.of("客户资料")));
        assertFalse(TrustedPageDraftIntent.matches("帮我改一下个人信息", List.of("客户资料")));
        assertFalse(TrustedPageDraftIntent.matches("帮我改一下个人信息", List.of("个")));
        assertFalse(TrustedPageDraftIntent.matches("不要修改个人信息，查询订单", List.of("个人信息")));
        assertFalse(TrustedPageDraftIntent.matches("请解释怎么修改个人信息", List.of("个人信息")));
        assertFalse(TrustedPageDraftIntent.matches("如果修改个人信息会怎样", List.of("个人信息")));
        assertFalse(TrustedPageDraftIntent.matches("查看个人信息", List.of("个人信息")));
        Map<String, String> labels = Map.of("nickname", "昵称", "address", "地址");
        assertTrue(TrustedPageDraftIntent.valuesAppearInMessage("昵称小黑，地址 北京路1号", Map.of("nickname", "小黑", "address", "北京路1号"), labels));
        assertFalse(TrustedPageDraftIntent.valuesAppearInMessage("昵称小黑", Map.of("nickname", "小"), labels));
        assertFalse(TrustedPageDraftIntent.valuesAppearInMessage("昵称小黑", Map.of("nickname", "小白"), labels));
        assertFalse(TrustedPageDraftIntent.valuesAppearInMessage("昵称小黑，地址北京路1号", Map.of("nickname", "北京路1号"), labels));
        assertFalse(TrustedPageDraftIntent.valuesAppearInMessage("昵称小黑，昵称小白", Map.of("nickname", "小黑"), labels));
    }
}
