package com.enterprise.ai.runtime.memory;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import redis.clients.jedis.JedisPooled;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

@Configuration
@EnableConfigurationProperties({
        RuntimeSessionMemoryProperties.class,
        RuntimeSessionRetentionProperties.class
})
public class RuntimeSessionMemoryConfiguration {

    @Bean(destroyMethod = "close")
    public AgentStateStore runtimeAgentStateStore(RuntimeSessionMemoryProperties properties,
                                                  Environment environment) {
        if (!properties.enabled()) {
            return new InMemoryAgentStateStore();
        }
        String type = properties.stateStore().trim().toLowerCase(Locale.ROOT);
        if (properties.requireRedisInProduction() && isProduction(environment) && !"redis".equals(type)) {
            throw new IllegalStateException(
                    "reachai.runtime.session-memory.state-store must be redis in production");
        }
        AgentStateStore delegate = switch (type) {
            case "redis" -> RedisAgentStateStore.builder()
                    .keyPrefix(properties.redisKeyPrefix())
                    .jedisClient(new JedisPooled(URI.create(properties.redisUri())))
                    .build();
            case "json" -> new JsonFileAgentStateStore(Path.of(expandUserHome(properties.jsonDirectory())));
            case "memory", "in-memory" -> new InMemoryAgentStateStore();
            default -> throw new IllegalArgumentException(
                    "Unsupported reachai.runtime.session-memory.state-store: " + properties.stateStore());
        };
        return new WindowedAgentStateStore(delegate, properties.maxContextMessages());
    }

    private static boolean isProduction(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .anyMatch(profile -> "prod".equals(profile) || "production".equals(profile));
    }

    private static String expandUserHome(String value) {
        return value.replace("${user.home}", System.getProperty("user.home"));
    }
}
