package com.enterprise.ai.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestTemplateConfigTest {

    @Test
    void createsRestTemplateWithSimpleRequestFactory() {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();

        assertTrue(restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory);
    }

    @Test
    void configuresTenSecondConnectTimeout() {
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) new RestTemplateConfig().restTemplate().getRequestFactory();

        assertEquals(10_000, (Integer) ReflectionTestUtils.getField(factory, "connectTimeout"));
    }

    @Test
    void configuresSixtySecondReadTimeout() {
        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) new RestTemplateConfig().restTemplate().getRequestFactory();

        assertEquals(60_000, (Integer) ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    @Test
    void registersRestTemplateBeanWhenMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(RestTemplateConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RestTemplate.class);
                    RestTemplate restTemplate = context.getBean(RestTemplate.class);
                    assertTrue(restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory);
                });
    }

    @Test
    void backsOffWhenCustomRestTemplateExists() {
        RestTemplate custom = new RestTemplate();
        new ApplicationContextRunner()
                .withUserConfiguration(RestTemplateConfig.class)
                .withBean("customRestTemplate", RestTemplate.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(RestTemplate.class);
                    assertSame(custom, context.getBean(RestTemplate.class));
                });
    }
}
