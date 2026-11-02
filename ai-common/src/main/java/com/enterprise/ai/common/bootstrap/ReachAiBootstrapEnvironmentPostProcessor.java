package com.enterprise.ai.common.bootstrap;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * First-start convenience shared by the five services, so a new installation only needs a
 * database:
 * <ol>
 *   <li>reads the user's {@code reachai.env} (process environment still wins);</li>
 *   <li>keeps retired environment variable names working, with a warning;</li>
 *   <li>creates the schema when the database is empty;</li>
 *   <li>generates the installation's secrets on that first start and reuses them afterwards.</li>
 * </ol>
 * Explicit configuration always wins. Kubernetes keeps its explicit, fail-fast secrets.
 */
public final class ReachAiBootstrapEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The "-systemEnvironment" suffix makes Spring Boot bind env-style names (EAF_EMBED_TOKEN_SECRET) here too. */
    static final String CONFIG_FILE_SOURCE = "reachaiConfigFile-systemEnvironment";
    static final String RETIRED_ENV_SOURCE = "reachaiRetiredEnvironmentVariables";
    public static final String GENERATED_SECRETS_SOURCE = "reachaiGeneratedSecrets";
    public static final String SECRETS_FILE_PROPERTY = "reachai.bootstrap.secrets-file";
    static final String RETIRED_ENV_RESOURCE = "reachai/retired-environment-variables.properties";
    static final Set<String> SERVICES = Set.of(
            "reachai-model-service", "reachai-knowledge-service", "reachai-capability-service",
            "reachai-runtime-service", "reachai-control-service");

    private final Log log;

    public ReachAiBootstrapEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(ReachAiBootstrapEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        // After application.yml is loaded, before the development defaults fill remaining gaps.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String applicationName = environment.getProperty("spring.application.name", "").trim();
        if (!SERVICES.contains(applicationName)
                || !environment.getProperty("reachai.bootstrap.enabled", Boolean.class, true)) {
            return;
        }
        loadConfigFile(environment);
        applyRetiredEnvironmentVariables(environment);
        bootstrapDatabaseAndSecrets(environment, applicationName);
    }

    private void loadConfigFile(ConfigurableEnvironment environment) {
        Path file = ReachAiHome.configFile(environment);
        if (file == null) {
            return;
        }
        try {
            Map<String, Object> values = EnvFile.read(file);
            addAfterEnvironment(environment, new SystemEnvironmentPropertySource(CONFIG_FILE_SOURCE, values));
            log.info("ReachAI 读取配置文件 " + file + "（" + values.size() + " 项；同名环境变量优先）");
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取配置文件 " + file + "：" + failure.getMessage(), failure);
        }
    }

    private void applyRetiredEnvironmentVariables(ConfigurableEnvironment environment) {
        Properties retired = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RETIRED_ENV_RESOURCE)) {
            if (input == null) {
                return;
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                retired.load(reader);
            }
        } catch (IOException failure) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : retired.stringPropertyNames()) {
            String value = rawValue(environment, name);
            if (value == null) {
                continue;
            }
            String[] properties = retired.getProperty(name).split(",");
            for (String property : properties) {
                values.put(property.trim(), value);
            }
            log.warn("环境变量 " + name + " 已不再单独列出，本次仍按旧值生效；以后直接设置配置项 "
                    + String.join(" / ", properties) + "，或删掉它使用默认值");
        }
        if (!values.isEmpty()) {
            addAfterEnvironment(environment, new MapPropertySource(RETIRED_ENV_SOURCE, values));
        }
    }

    private void bootstrapDatabaseAndSecrets(ConfigurableEnvironment environment, String applicationName) {
        boolean kubernetes = StringUtils.hasText(environment.getProperty("KUBERNETES_SERVICE_HOST"));
        boolean generateSecrets = !kubernetes
                && environment.getProperty("reachai.bootstrap.generate-secrets", Boolean.class, true);
        Path secretsFile = ReachAiHome.secretsFile(environment);
        if (generateSecrets && GeneratedSecrets.exists(secretsFile)) {
            useSecrets(environment, secretsFile, applicationName);
            generateSecrets = false;
        }

        String url = resolve(environment, "spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            return;
        }
        String password = resolve(environment, "spring.datasource.password");
        if (password == null) {
            log.error("缺少数据库密码：请在 reachai.env 中填写 AI_MYSQL_PASSWORD（也可设置同名环境变量）");
            return;
        }
        boolean createSchema = environment.getProperty("reachai.bootstrap.schema-init", Boolean.class, true);
        boolean generateInsideLock = generateSecrets;
        try {
            SchemaBootstrap.State state = SchemaBootstrap.run(
                    url, resolve(environment, "spring.datasource.username"), password, createSchema,
                    found -> {
                        if (found == SchemaBootstrap.State.CREATED && createSchema) {
                            log.info("ReachAI 检测到空数据库，已按内置 initV2.sql 建好表和初始数据");
                        }
                        if (generateInsideLock && !GeneratedSecrets.exists(secretsFile)
                                && found == SchemaBootstrap.State.CREATED) {
                            GeneratedSecrets.createIfAbsent(secretsFile);
                            log.info("ReachAI 首次启动，已生成本机密钥 " + secretsFile
                                    + "。请和数据库一起备份，丢失后已保存的模型密钥等将无法解密");
                        }
                    });
            if (generateInsideLock && GeneratedSecrets.exists(secretsFile)) {
                useSecrets(environment, secretsFile, applicationName);
            } else if (generateInsideLock && state == SchemaBootstrap.State.EXISTING) {
                log.warn("ReachAI 数据库已有数据，但未找到 " + secretsFile + "，沿用已有密钥配置。"
                        + "如果其他服务已经生成过这个文件，说明 5 个服务没用同一个 REACHAI_HOME，请改成同一个目录");
            }
        } catch (Exception failure) {
            String reason = failure.getMessage();
            if (failure.getMessage() != null && failure.getMessage().startsWith("Schema initialization failed")) {
                throw new IllegalStateException("自动建表失败（" + reason + "）。请清空数据库 "
                        + url + " 后重新启动，或手动执行 sql/initV2.sql", failure);
            }
            log.warn("ReachAI 启动前无法连接数据库 " + url + "：" + reason
                    + "。请确认 MySQL 已启动、AI_MYSQL_HOST/AI_MYSQL_PORT/AI_MYSQL_USER/AI_MYSQL_PASSWORD 正确");
        }
    }

    private void useSecrets(ConfigurableEnvironment environment, Path secretsFile, String applicationName) {
        try {
            Map<String, Object> secrets = new LinkedHashMap<>(GeneratedSecrets.load(secretsFile));
            secrets.keySet().removeIf(property -> StringUtils.hasText(environment.getProperty(property)));
            // Lets the system check page say where the file lives, so it gets backed up.
            secrets.put(SECRETS_FILE_PROPERTY, secretsFile.toString());
            // Explicit values were filtered out above, so ranking above application.yml only fills
            // its empty "${SECRET:}" placeholders.
            addAfterEnvironment(environment, new MapPropertySource(GENERATED_SECRETS_SOURCE, secrets));
            String internal = environment.getProperty("reachai.internal.service-secret");
            if (StringUtils.hasText(internal)) {
                log.info("ReachAI " + applicationName + " 内部通信密钥指纹 " + fingerprint(internal)
                        + "（5 个服务必须一致）");
            }
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取密钥文件 " + secretsFile + "：" + failure.getMessage(), failure);
        }
    }

    /** Lowest priority that is still above application.yml: right after the process environment. */
    private static void addAfterEnvironment(ConfigurableEnvironment environment, MapPropertySource source) {
        // SystemEnvironmentPropertySource extends MapPropertySource.
        MutablePropertySources sources = environment.getPropertySources();
        sources.remove(source.getName());
        String anchor = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (sources.contains(CONFIG_FILE_SOURCE) && !CONFIG_FILE_SOURCE.equals(source.getName())) {
            anchor = CONFIG_FILE_SOURCE;
        }
        if (sources.contains(anchor)) {
            sources.addAfter(anchor, source);
        } else {
            sources.addFirst(source);
        }
    }

    private static String rawValue(ConfigurableEnvironment environment, String name) {
        Object value = Stream.of(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, CONFIG_FILE_SOURCE)
                .map(environment.getPropertySources()::get)
                .filter(source -> source != null)
                .map(source -> source.getProperty(name))
                .filter(found -> found != null && StringUtils.hasText(String.valueOf(found)))
                .findFirst()
                .orElse(null);
        return value == null ? null : String.valueOf(value);
    }

    private static String resolve(ConfigurableEnvironment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value == null ? null : value.trim();
        } catch (IllegalArgumentException unresolved) {
            return null;
        }
    }

    static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(Arrays.copyOf(digest, 4)).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
