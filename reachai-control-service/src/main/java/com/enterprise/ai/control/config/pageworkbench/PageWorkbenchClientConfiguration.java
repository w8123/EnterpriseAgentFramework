package com.enterprise.ai.control.config.pageworkbench;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/** Composition root binds Page Workbench ports without making shared clients depend on application modules. */
@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = PageWorkbenchRuntimeClient.class)
public class PageWorkbenchClientConfiguration {
}
