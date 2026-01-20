package com.company.observability.config;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;

import java.time.Duration;
import java.util.*;

@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * Optimized Lettuce connection factory with connection pooling
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Socket options for better performance
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .keepAlive(true)
                .build();

        // Client options
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();

        // Lettuce client configuration
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(2))
                .build();

        // Redis standalone configuration
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(System.getenv().getOrDefault("REDIS_HOST", "localhost"));
        serverConfig.setPort(Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));

        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) {
            serverConfig.setPassword(password);
        }

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use Jackson serializer for better performance
        Jackson2JsonRedisSerializer<Object> serializer = jackson2JsonRedisSerializer();

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.setEnableTransactionSupport(false); // Better performance
        template.afterPropertiesSet();

        return template;
    }

    /**
     * Multi-tier cache manager with optimized TTLs
     */
    @Bean
    @Primary
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper());

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
                .disableCachingNullValues()
                .prefixCacheNameWith("obs:"); // Namespace prefix

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // ============================================================
        // HOT CACHE: Frequently accessed, short TTL
        // ============================================================

        // Current calculator status (very hot)
        cacheConfigurations.put("calculatorStatus",
                defaultConfig.entryTtl(Duration.ofMinutes(5)));

        // Batch status queries (hot)
        cacheConfigurations.put("batchCalculatorStatus",
                defaultConfig.entryTtl(Duration.ofMinutes(3)));

        // Running calculators count (very hot)
        cacheConfigurations.put("runningCount",
                defaultConfig.entryTtl(Duration.ofMinutes(1)));

        // ============================================================
        // WARM CACHE: Moderate access, medium TTL
        // ============================================================

        // Recent runs by frequency - DAILY (moderate)
        cacheConfigurations.put("recentRuns:DAILY",
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Recent runs by frequency - MONTHLY (less frequent)
        cacheConfigurations.put("recentRuns:MONTHLY",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        // Calculator statistics (moderate)
        cacheConfigurations.put("calculatorStats",
                defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // Daily aggregates (moderate)
        cacheConfigurations.put("dailyAggregates",
                defaultConfig.entryTtl(Duration.ofHours(2)));

        // ============================================================
        // COLD CACHE: Rare access, long TTL
        // ============================================================

        // Calculator metadata (extracted from recent run)
        cacheConfigurations.put("calculatorMetadata",
                defaultConfig.entryTtl(Duration.ofHours(6)));

        // Active calculators list
        cacheConfigurations.put("activeCalculators",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        // Historical statistics
        cacheConfigurations.put("historicalStats",
                defaultConfig.entryTtl(Duration.ofHours(12)));

        // ============================================================
        // PERSISTENCE CACHE: Very long TTL for rarely changing data
        // ============================================================

        // SLA configurations (extracted from runs)
        cacheConfigurations.put("slaConfigs",
                defaultConfig.entryTtl(Duration.ofHours(24)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .transactionAware()
                .build();
    }

    private ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    private Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer() {
        return new Jackson2JsonRedisSerializer<>(objectMapper(), Object.class);
    }
}