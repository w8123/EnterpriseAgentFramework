package com.enterprise.ai.control.activity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActivitySanitizerTest {

    @Test
    void masksPersonalDataInFreeText() {
        String masked = ActivitySanitizer.text("客户 13812345678 邮箱 a.b@corp.com 身份证 11010119900307123X 密码: abc123", 300);
        assertThat(masked).contains("138****5678").contains("***@***").doesNotContain("11010119900307123X")
                .doesNotContain("abc123");
    }

    @Test
    void collapsesIdsAndDropsQueryInPaths() {
        assertThat(ActivitySanitizer.operationKey("post /admin-api/erp/customer/12/update?x=1"))
                .isEqualTo("POST /admin-api/erp/customer/{id}/update");
        assertThat(ActivitySanitizer.path("https://erp.example.com/erp/sale/order/edit/42#top"))
                .isEqualTo("/erp/sale/order/edit/{id}");
        assertThat(ActivitySanitizer.path("erp/customer")).isEqualTo("/erp/customer");
    }

    @Test
    void truncatesByCodePoint() {
        assertThat(ActivitySanitizer.text("客户名称太长了", 4)).isEqualTo("客户名称");
    }
}
