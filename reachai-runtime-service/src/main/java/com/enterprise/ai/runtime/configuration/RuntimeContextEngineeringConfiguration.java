package com.enterprise.ai.runtime.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Runtime 共用配置的绑定入口，不依赖 Supervisor 或记忆存储组件的初始化。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeContextEngineeringProperties.class)
public class RuntimeContextEngineeringConfiguration {
}
