package com.ratelimiter.config;

import com.ratelimiter.service.TokenBucket;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configuration properties for the Token Bucket rate limiter.
 * Values are loaded from application.yml under the 'rate-limiter' prefix.
 */
@Configuration
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterConfig {

    private TokenBucketProperties tokenBucket = new TokenBucketProperties();

    public TokenBucketProperties getTokenBucket() {
        return tokenBucket;
    }

    public void setTokenBucket(TokenBucketProperties tokenBucket) {
        this.tokenBucket = tokenBucket;
    }

    /**
     * Creates a TokenBucket bean using the configured properties.
     *
     * @return TokenBucket instance
     */
    @Bean
    public TokenBucket tokenBucket() {
        return new TokenBucket(
            tokenBucket.getCapacity(),
            tokenBucket.getRefillRate()
        );
    }

    /**
     * Creates a RedisTemplate bean for Redis operations.
     *
     * @param connectionFactory the Redis connection factory
     * @return configured RedisTemplate instance
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use StringRedisSerializer for keys
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        
        // Use default serializer for values
        template.setValueSerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Token Bucket specific configuration properties.
     */
    public static class TokenBucketProperties {

        /**
         * Maximum number of tokens the bucket can hold.
         */
        private long capacity = 100;

        /**
         * Rate at which tokens are refilled (tokens per second).
         */
        private double refillRate = 10.0;

        /**
         * Whether the rate limiter is enabled.
         */
        private boolean enabled = true;

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public double getRefillRate() {
            return refillRate;
        }

        public void setRefillRate(double refillRate) {
            this.refillRate = refillRate;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
