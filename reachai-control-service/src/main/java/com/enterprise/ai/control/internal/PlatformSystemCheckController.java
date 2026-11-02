package com.enterprise.ai.control.internal;

import com.enterprise.ai.common.bootstrap.ReachAiBootstrapEnvironmentPostProcessor;
import com.enterprise.ai.common.config.ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor;
import com.enterprise.ai.control.client.knowledge.KnowledgeHealthClient;
import com.enterprise.ai.control.identity.PlatformAuthProperties;
import com.enterprise.ai.control.identity.PlatformDefaultAdminPasswordCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One page that tells an administrator whether this installation is healthy and safe, and
 * how to fix each problem. Every item carries a plain-language fix, because the reader is
 * often the person who just ran ./dev.sh for the first time.
 */
@RestController
public class PlatformSystemCheckController {

    private static final Logger log = LoggerFactory.getLogger(PlatformSystemCheckController.class);

    static final String OK = "OK";
    static final String WARN = "WARN";
    static final String ERROR = "ERROR";
    static final String OFF = "OFF";

    private static final Map<String, String> SERVICE_TITLES = new LinkedHashMap<>();

    static {
        SERVICE_TITLES.put("model", "模型服务");
        SERVICE_TITLES.put("knowledge", "知识库服务");
        SERVICE_TITLES.put("capability", "业务能力服务");
        SERVICE_TITLES.put("runtime", "运行服务");
    }

    private final InternalServicesHealthController servicesHealth;
    private final KnowledgeHealthClient knowledgeHealthClient;
    private final ConfigurableEnvironment environment;
    private final PlatformDefaultAdminPasswordCheck adminPasswordCheck;

    public PlatformSystemCheckController(InternalServicesHealthController servicesHealth,
                                         KnowledgeHealthClient knowledgeHealthClient,
                                         ConfigurableEnvironment environment,
                                         PlatformDefaultAdminPasswordCheck adminPasswordCheck) {
        this.servicesHealth = servicesHealth;
        this.knowledgeHealthClient = knowledgeHealthClient;
        this.environment = environment;
        this.adminPasswordCheck = adminPasswordCheck;
    }

    @GetMapping("/api/platform/system-check")
    public Map<String, Object> systemCheck() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("database", "services", "数据库", OK, "管理服务已连上数据库 " + databaseLabel(), null, null));
        items.add(item("control", "services", "管理服务", OK, "正在为你提供这个页面", null, null));
        Boolean knowledgeUp = serviceItems(items);
        items.add(secretsItem());
        items.add(adminPasswordItem());
        dependencyItems(items, Boolean.TRUE.equals(knowledgeUp));

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (String status : List.of(OK, WARN, ERROR, OFF)) {
            summary.put(status, (int) items.stream().filter(entry -> status.equals(entry.get("status"))).count());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkedAt", OffsetDateTime.now().toString());
        body.put("summary", summary);
        body.put("items", items);
        return body;
    }

    /** Returns whether Knowledge is up, so its dependencies are only probed when it can answer. */
    @SuppressWarnings("unchecked")
    private Boolean serviceItems(List<Map<String, Object>> items) {
        Map<String, Object> services = Map.of();
        Object body = servicesHealth.health().getBody();
        if (body instanceof Map<?, ?> map && map.get("services") instanceof Map<?, ?> found) {
            services = (Map<String, Object>) found;
        }
        Boolean knowledgeUp = null;
        for (Map.Entry<String, String> service : SERVICE_TITLES.entrySet()) {
            Object state = services.get(service.getKey());
            boolean up = state instanceof Map<?, ?> stateMap && "UP".equals(stateMap.get("status"));
            if ("knowledge".equals(service.getKey())) {
                knowledgeUp = up;
            }
            items.add(up
                    ? item(service.getKey(), "services", service.getValue(), OK, "运行正常", null, null)
                    : item(service.getKey(), "services", service.getValue(), ERROR, "没有响应",
                    "本机开发：运行 ./dev.sh status 查看状态，日志在 .reachai/logs/" + service.getKey()
                            + ".log；服务器：确认该服务已启动，并且管理服务配置的地址能访问到它。",
                    null));
        }
        return knowledgeUp;
    }

    private Map<String, Object> secretsItem() {
        PropertySource<?> developmentDefaults = environment.getPropertySources()
                .get(ReachAiLocalDevelopmentDefaultsEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        if (developmentDefaults != null && developmentDefaults.containsProperty("reachai.internal.service-secret")) {
            return item("secrets", "security", "加密与签名密钥", WARN,
                    "正在使用代码里公开的开发密钥（这个数据库是在自动生成密钥之前建的）",
                    "只在本机试用没关系。要给别人用之前，请为五个服务配置同一组新密钥（见 reachai.env.example 的「一般不用改」部分），"
                            + "或者换一个空数据库重新启动，让系统自动生成。",
                    null);
        }
        String generatedFile = environment.getProperty(ReachAiBootstrapEnvironmentPostProcessor.SECRETS_FILE_PROPERTY);
        if (environment.getPropertySources().contains(ReachAiBootstrapEnvironmentPostProcessor.GENERATED_SECRETS_SOURCE)
                && StringUtils.hasText(generatedFile)) {
            return item("secrets", "security", "加密与签名密钥", OK,
                    "首次启动时自动生成，保存在 " + generatedFile,
                    "请把这个文件和数据库一起备份：丢了它，已保存的模型密钥等就解不开了。", null);
        }
        return item("secrets", "security", "加密与签名密钥", OK, "使用你配置的密钥", null, null);
    }

    private Map<String, Object> adminPasswordItem() {
        String username = adminPasswordCheck.bootstrapUsername();
        try {
            if (adminPasswordCheck.usesDefaultPassword()) {
                return item("admin-password", "security", "管理员密码", WARN,
                        "账号 " + username + " 还在用默认密码 " + PlatformAuthProperties.DEVELOPMENT_ADMIN_PASSWORD,
                        "让别人访问之前，请在「平台账号」里给它重置一个新密码。", "/settings/platform-users");
            }
        } catch (RuntimeException failure) {
            log.warn("System check could not read the bootstrap administrator: {}", failure.toString());
            return item("admin-password", "security", "管理员密码", WARN, "暂时无法检查", null, null);
        }
        return item("admin-password", "security", "管理员密码", OK, "已不是默认密码", null, null);
    }

    @SuppressWarnings("unchecked")
    private void dependencyItems(List<Map<String, Object>> items, boolean knowledgeUp) {
        Map<String, Object> dependencies = Map.of();
        if (knowledgeUp) {
            try {
                Map<String, Object> info = knowledgeHealthClient.info();
                if (info != null && info.get("dependencies") instanceof Map<?, ?> found) {
                    dependencies = (Map<String, Object>) found;
                }
            } catch (RuntimeException failure) {
                log.warn("System check could not read Knowledge dependencies: {}", failure.toString());
            }
        }
        Map<String, Object> milvus = asMap(dependencies.get("milvus"));
        if (milvus.isEmpty()) {
            items.add(item("milvus", "optional", "Milvus 向量库", WARN, "知识库服务没有响应，暂时无法检查", null, null));
        } else if (Boolean.TRUE.equals(milvus.get("reachable"))) {
            items.add(item("milvus", "optional", "Milvus 向量库", OK, "已连接 " + milvus.get("address"), null, null));
        } else {
            items.add(item("milvus", "optional", "Milvus 向量库", OFF,
                    "未连接（" + milvus.get("address") + "）。不影响其他功能，知识库的向量检索暂时用不了",
                    "需要知识库时：本机运行 docker compose -f deploy/docker-compose.infra.yml --profile milvus up -d；"
                            + "已有 Milvus 就在 reachai.env 里填写 MILVUS_HOST / MILVUS_PORT，然后重启知识库服务。",
                    null));
        }
        Map<String, Object> docling = asMap(dependencies.get("docling"));
        if (docling.isEmpty()) {
            items.add(item("docling", "optional", "Docling 文档解析", WARN, "知识库服务没有响应，暂时无法检查", null, null));
        } else if (!Boolean.TRUE.equals(docling.get("enabled"))) {
            items.add(item("docling", "optional", "Docling 文档解析", OFF, "已关闭，知识库只能导入 txt / md / csv", null, null));
        } else if (Boolean.TRUE.equals(docling.get("reachable"))) {
            items.add(item("docling", "optional", "Docling 文档解析", OK, "已连接 " + docling.get("address"), null, null));
        } else {
            items.add(item("docling", "optional", "Docling 文档解析", OFF,
                    "未连接（" + docling.get("address") + "）。知识库暂时只能导入 txt / md / csv",
                    "需要导入 PDF、Word、PPT、Excel 时：本机运行 docker compose -f deploy/docker-compose.infra.yml --profile docling up -d；"
                            + "已有 Docling 就在 reachai.env 里填写 REACHAI_DOCLING_BASE_URL，然后重启知识库服务。",
                    null));
        }
    }

    private String databaseLabel() {
        String url = environment.getProperty("spring.datasource.url", "");
        int query = url.indexOf('?');
        String withoutQuery = query >= 0 ? url.substring(0, query) : url;
        return withoutQuery.replaceFirst("^jdbc:mysql://", "").replaceFirst("^[^@/]*@", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    static Map<String, Object> item(String key, String group, String title, String status,
                                    String detail, String fix, String link) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("group", group);
        item.put("title", title);
        item.put("status", status);
        item.put("detail", detail);
        if (fix != null) {
            item.put("fix", fix);
        }
        if (link != null) {
            item.put("link", link);
        }
        return item;
    }
}
