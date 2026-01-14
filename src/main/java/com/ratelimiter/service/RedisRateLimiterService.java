package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Redis-based distributed rate limiter service using Token Bucket algorithm.
 *
 * This implementation uses Redis to store rate limiting state, allowing multiple
 * application instances to share the same rate limiting data. Lua scripts ensure
 * atomic operations on Redis.
 *
 * Redis Key Structure:
 *   Key: "rate_limit:{user_id}"
 *   Fields (Hash):
 *     - tokens: Current available tokens (double)
 *     - last_refill: Last refill timestamp in milliseconds (long)
 *   TTL: Set to 2x the time needed to fully refill the bucket
 */
@Service
public class RedisRateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimiterService.class);

    private static final String KEY_PREFIX = "rate_limit:";

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterConfig config;

    /**
     * Lua script for atomic token consumption with automatic refill.
     *
     * This script performs the following operations atomically:
     * 1. Retrieve current tokens and last refill timestamp from Redis
     * 2. Calculate elapsed time since last refill
     * 3. Calculate tokens to add based on elapsed time and refill rate
     * 4. Add tokens (up to capacity) and update last refill time
     * 5. Check if enough tokens are available for consumption
     * 6. If yes: consume tokens and return success
     * 7. If no: return failure with current available tokens
     *
     * KEYS[1]: Redis key for the rate limit bucket
     * ARGV[1]: Capacity (maximum tokens)
     * ARGV[2]: Refill rate (tokens per second)
     * ARGV[3]: Tokens to consume
     * ARGV[4]: Current timestamp in milliseconds
     * ARGV[5]: TTL in seconds
     *
     * Returns: [allowed (0 or 1), remaining_tokens]
     */
    private static final String LUA_SCRIPT_CONSUME = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local tokens_to_consume = tonumber(ARGV[3])
            local now = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])

            -- Get current state from Redis
            local current_tokens = tonumber(redis.call('HGET', key, 'tokens'))
            local last_refill = tonumber(redis.call('HGET', key, 'last_refill'))

            -- Initialize if this is a new key
            if current_tokens == nil then
                current_tokens = capacity
                last_refill = now
            end

            -- Calculate tokens to add based on elapsed time
            local elapsed_seconds = (now - last_refill) / 1000.0
            if elapsed_seconds > 0 then
                local tokens_to_add = elapsed_seconds * refill_rate
                current_tokens = math.min(capacity, current_tokens + tokens_to_add)
                last_refill = now
            end

            -- Check if we have enough tokens
            local allowed = 0
            if current_tokens >= tokens_to_consume then
                current_tokens = current_tokens - tokens_to_consume
                allowed = 1
            end

            -- Update Redis state
            redis.call('HSET', key, 'tokens', tostring(current_tokens))
            redis.call('HSET', key, 'last_refill', tostring(last_refill))
            redis.call('EXPIRE', key, ttl)

            -- Return: [allowed (0 or 1), remaining_tokens]
            return {allowed, math.floor(current_tokens)}
            """;

    public RedisRateLimiterService(StringRedisTemplate redisTemplate, RateLimiterConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        logger.info("RedisRateLimiterService initialized with capacity={}, refillRate={}",
                config.getTokenBucket().getCapacity(),
                config.getTokenBucket().getRefillRate());
    }

    /**
     * Attempts to consume tokens from the rate limit bucket for a specific user.
     *
     * @param userId The user identifier (e.g., API key, user ID, or "anonymous")
     * @param tokensToConsume Number of tokens to consume
     * @return true if tokens were successfully consumed, false if rate limit exceeded
     */
    public boolean tryConsume(String userId, long tokensToConsume) {
        if (!config.getTokenBucket().isEnabled()) {
            logger.debug("Rate limiting is disabled, allowing request for user: {}", userId);
            return true;
        }

        String key = KEY_PREFIX + userId;
        long capacity = config.getTokenBucket().getCapacity();
        double refillRate = config.getTokenBucket().getRefillRate();
        long currentTimeMillis = System.currentTimeMillis();

        // Calculate TTL: 2x the time needed to fully refill the bucket
        long ttl = (long) Math.ceil((capacity / refillRate) * 2);

        try {
            // Execute Lua script atomically
            RedisScript<List<Long>> script = RedisScript.of(LUA_SCRIPT_CONSUME, (Class<List<Long>>) (Class<?>) List.class);
            List<Long> result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(tokensToConsume),
                    String.valueOf(currentTimeMillis),
                    String.valueOf(ttl)
            );

            if (result != null && result.size() == 2) {
                int allowed = ((Number) result.get(0)).intValue();
                long remainingTokens = ((Number) result.get(1)).longValue();

                if (allowed == 1) {
                    logger.debug("Request allowed for user: {}, remaining tokens: {}", userId, remainingTokens);
                    return true;
                } else {
                    logger.debug("Request rate limited for user: {}, remaining tokens: {}", userId, remainingTokens);
                    return false;
                }
            }

            logger.error("Unexpected result from Lua script: {}", result);
            return false;

        } catch (Exception e) {
            logger.error("Error executing rate limit check for user: {}", userId, e);
            // Fail open: allow request if Redis is unavailable
            return true;
        }
    }

    /**
     * Attempts to consume a single token.
     *
     * @param userId The user identifier
     * @return true if token was successfully consumed, false if rate limit exceeded
     */
    public boolean tryConsume(String userId) {
        return tryConsume(userId, 1);
    }

    /**
     * Gets the current number of available tokens for a user.
     *
     * @param userId The user identifier
     * @return The number of available tokens, or capacity if user has no rate limit data
     */
    public long getAvailableTokens(String userId) {
        try {
            String key = KEY_PREFIX + userId;
            String tokensStr = redisTemplate.opsForHash().get(key, "tokens").toString();
            return tokensStr != null ? (long) Double.parseDouble(tokensStr) : config.getTokenBucket().getCapacity();
        } catch (Exception e) {
            logger.error("Error getting available tokens for user: {}", userId, e);
            return config.getTokenBucket().getCapacity();
        }
    }

    /**
     * Gets the maximum capacity of the token bucket.
     *
     * @return The bucket capacity
     */
    public long getCapacity() {
        return config.getTokenBucket().getCapacity();
    }

    /**
     * Resets the rate limit for a specific user.
     *
     * @param userId The user identifier
     */
    public void reset(String userId) {
        try {
            String key = KEY_PREFIX + userId;
            redisTemplate.delete(key);
            logger.info("Reset rate limit for user: {}", userId);
        } catch (Exception e) {
            logger.error("Error resetting rate limit for user: {}", userId, e);
        }
    }

    /**
     * Resets all rate limits by deleting all rate limit keys.
     * Use with caution in production!
     */
    public void resetAll() {
        try {
            redisTemplate.keys(KEY_PREFIX + "*").forEach(redisTemplate::delete);
            logger.warn("Reset all rate limits - all keys deleted");
        } catch (Exception e) {
            logger.error("Error resetting all rate limits", e);
        }
    }
}
