package com.enterprise.ai.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RedisConfigTest {

    @Test
    void usesProvidedConnectionFactoryWithoutConnecting() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);

        RedisTemplate<String, Object> template = new RedisConfig().redisTemplate(factory);

        assertSame(factory, template.getConnectionFactory());
        verifyNoInteractions(factory);
    }

    @Test
    void usesUtf8StringSerializersForKeysAndHashKeys() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> template = new RedisConfig().redisTemplate(factory);

        assertTrue(template.getKeySerializer() instanceof StringRedisSerializer);
        assertTrue(template.getHashKeySerializer() instanceof StringRedisSerializer);

        String key = "\u4E2D\u6587";
        assertArrayEquals(
                key.getBytes(StandardCharsets.UTF_8),
                ((StringRedisSerializer) template.getKeySerializer()).serialize(key));
        verifyNoInteractions(factory);
    }

    @Test
    void sharesJacksonSerializerForValuesAndHashValues() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> template = new RedisConfig().redisTemplate(factory);

        assertTrue(template.getValueSerializer() instanceof Jackson2JsonRedisSerializer);
        assertTrue(template.getHashValueSerializer() instanceof Jackson2JsonRedisSerializer);
        assertSame(template.getValueSerializer(), template.getHashValueSerializer());
        verifyNoInteractions(factory);
    }

    @SuppressWarnings("unchecked")
    @Test
    void jacksonValueSerializerRoundTripsNonFinalCollection() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> template = new RedisConfig().redisTemplate(factory);

        RedisSerializer<Object> serializer = (RedisSerializer<Object>) template.getValueSerializer();
        ArrayList<String> original = new ArrayList<>(List.of("alpha", "beta", "gamma"));

        byte[] bytes = serializer.serialize(original);
        Object result = serializer.deserialize(bytes);

        assertTrue(result instanceof ArrayList);
        assertEquals(original, result);
        verifyNoInteractions(factory);
    }
}
