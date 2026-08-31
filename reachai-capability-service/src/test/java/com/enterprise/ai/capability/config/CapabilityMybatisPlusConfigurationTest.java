package com.enterprise.ai.capability.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CapabilityMybatisPlusConfigurationTest {

    @Test
    void installsPaginationInterceptorForCapabilityCatalogPages() {
        MybatisPlusInterceptor interceptor =
                new CapabilityMybatisPlusConfiguration().capabilityMybatisPlusInterceptor();

        assertEquals(1, interceptor.getInterceptors().size());
        assertInstanceOf(PaginationInnerInterceptor.class, interceptor.getInterceptors().get(0));
    }
}
