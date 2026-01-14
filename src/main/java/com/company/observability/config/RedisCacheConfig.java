package com.company.observability.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.*;
import java.time.Duration;
import java.util.*;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory();
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jackson2JsonRedisSerializer());
        return template;
    }

    /**
     * GT Enhancement: Redis Message Listener Container for keyspace events
     * Required for SLA timer expiry notifications
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Configure ObjectMapper for proper JSON serialization
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(serializer)
                )
                .disableCachingNullValues();

        // Specific cache configurations with optimized TTLs
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Recent runs - DAILY frequency (30 min TTL)
        // Calculator runs every 24h, runtime 15-90 min, so 30min cache is perfect
        cacheConfigurations.put("recentRuns:DAILY",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Recent runs - MONTHLY frequency (2 hour TTL)
        // Calculator runs monthly, can cache aggressively
        cacheConfigurations.put("recentRuns:MONTHLY",
                defaultConfig.entryTtl(Duration.ofHours(2)));

        // Batch queries (5 min TTL)
        // Shorter because any calculator change affects the batch
        cacheConfigurations.put("batchRecentRuns",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // Average runtime (30 min TTL)
        cacheConfigurations.put("avgRuntime",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Calculator metadata (24 hour TTL)
        // Rarely changes
        cacheConfigurations.put("calculators",
                defaultConfig.entryTtl(Duration.ofHours(24)));

        // Run details (15 min TTL)
        cacheConfigurations.put("runDetails",
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // GT Enhancement: Daily aggregates cache (6 hour TTL)
        // Daily aggregates change once per day max
        cacheConfigurations.put("dailyAggregates",
                defaultConfig.entryTtl(Duration.ofHours(6)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    private GenericJackson2JsonRedisSerializer jackson2JsonRedisSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }
}