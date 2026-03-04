package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed Token Bucket rate limiter with automatic in-memory fallback.
 *
 * When Redis is unreachable (e.g. Railway without a Redis add-on), the service
 * transparently switches to a ConcurrentHashMap + TokenBucket store so that
 * rate limiting still works instead of failing-open and allowing all requests.
 */
@Service
public class RedisRateLimiterService {

    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimiterService.class);
    private static final String KEY_PREFIX = "rate_limit:";

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterConfig config;

    // ── In-memory fallback (used when Redis is unavailable) ──────────────────
    private final ConcurrentHashMap<String, TokenBucket> fallbackBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>        fallbackAccess  = new ConcurrentHashMap<>();
    // volatile so threads see the flip immediately without locking
    private volatile boolean redisFailing = false;

    // ── Lua script: atomic refill-then-consume ────────────────────────────────
    private static final String LUA_SCRIPT_CONSUME = """
            local key               = KEYS[1]
            local capacity          = tonumber(ARGV[1])
            local refill_rate       = tonumber(ARGV[2])
            local tokens_to_consume = tonumber(ARGV[3])
            local now               = tonumber(ARGV[4])
            local ttl               = tonumber(ARGV[5])

            local current_tokens = tonumber(redis.call('HGET', key, 'tokens'))
            local last_refill    = tonumber(redis.call('HGET', key, 'last_refill'))

            if current_tokens == nil then
                current_tokens = capacity
                last_refill    = now
            end

            local elapsed_seconds = (now - last_refill) / 1000.0
            if elapsed_seconds > 0 then
                current_tokens = math.min(capacity, current_tokens + elapsed_seconds * refill_rate)
                last_refill    = now
            end

            local allowed = 0
            if current_tokens >= tokens_to_consume then
                current_tokens = current_tokens - tokens_to_consume
                allowed = 1
            end

            redis.call('HSET',   key, 'tokens',      tostring(current_tokens))
            redis.call('HSET',   key, 'last_refill', tostring(last_refill))
            redis.call('EXPIRE', key, ttl)

            return {allowed, math.floor(current_tokens)}
            """;

    @Autowired
    public RedisRateLimiterService(@Nullable StringRedisTemplate redisTemplate, RateLimiterConfig config) {
        this.redisTemplate = redisTemplate;
        this.config = config;
        if (redisTemplate == null) {
            redisFailing = true;
            logger.info("RedisRateLimiterService started in IN-MEMORY mode (no Redis) — capacity={}, refillRate={} tok/s",
                    config.getTokenBucket().getCapacity(), config.getTokenBucket().getRefillRate());
        } else {
            logger.info("RedisRateLimiterService ready — capacity={}, refillRate={} tok/s",
                    config.getTokenBucket().getCapacity(), config.getTokenBucket().getRefillRate());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean tryConsume(String userId) {
        return tryConsume(userId, 1);
    }

    /**
     * Attempts to consume {@code tokensToConsume} tokens for {@code userId}.
     * Falls back to in-memory when Redis is unavailable instead of failing-open.
     */
    @SuppressWarnings("unchecked")
    public boolean tryConsume(String userId, long tokensToConsume) {
        if (!config.getTokenBucket().isEnabled()) return true;

        // While Redis is known-failing, go straight to fallback (avoids connection
        // timeout penalty on every request).
        if (redisFailing) {
            return consumeFallback(userId, tokensToConsume);
        }

        try {
            long capacity    = config.getTokenBucket().getCapacity();
            double refillRate = config.getTokenBucket().getRefillRate();
            long   now       = System.currentTimeMillis();
            long   ttl       = (long) Math.ceil((capacity / refillRate) * 2);

            RedisScript<List<Long>> script =
                    RedisScript.of(LUA_SCRIPT_CONSUME, (Class<List<Long>>) (Class<?>) List.class);

            List<Long> result = redisTemplate.execute(
                    script,
                    Collections.singletonList(KEY_PREFIX + userId),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(tokensToConsume),
                    String.valueOf(now),
                    String.valueOf(ttl)
            );

            if (result != null && result.size() == 2) {
                redisFailing = false;   // Redis is healthy again
                boolean allowed = ((Number) result.get(0)).intValue() == 1;
                logger.debug("Redis rate-limit: user={} allowed={} remaining={}",
                        userId, allowed, result.get(1));
                return allowed;
            }
            // Unexpected result shape — treat as Redis problem
            return consumeFallback(userId, tokensToConsume);

        } catch (Exception e) {
            if (!redisFailing) {
                logger.warn("Redis unavailable — switching to in-memory fallback. ({})", e.getMessage());
                redisFailing = true;
            }
            return consumeFallback(userId, tokensToConsume);
        }
    }

    public long getAvailableTokens(String userId) {
        if (!redisFailing) {
            try {
                Object val = redisTemplate.opsForHash().get(KEY_PREFIX + userId, "tokens");
                if (val != null) return (long) Double.parseDouble(val.toString());
            } catch (Exception e) {
                // fall through to fallback
            }
        }
        TokenBucket bucket = fallbackBuckets.get(userId);
        return bucket == null ? config.getTokenBucket().getCapacity() : (long) bucket.getAvailableTokens();
    }

    public long getCapacity() {
        return config.getTokenBucket().getCapacity();
    }

    /**
     * Returns the set of user IDs that have made at least one request.
     * Reads from Redis when available; falls back to in-memory tracking.
     *
     * Bug-fix: previous code scanned "rate_limit:*:tokens" — a STRING key pattern.
     * Actual keys are "rate_limit:{userId}" which are HASH type keys. Pattern
     * never matched, so active-users always returned empty.
     */
    public Set<String> getActiveUsers() {
        if (!redisFailing) {
            try {
                Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*"); // ← fixed pattern
                if (keys != null && !keys.isEmpty()) {
                    Set<String> users = new HashSet<>();
                    for (String key : keys) {
                        users.add(key.substring(KEY_PREFIX.length())); // strip prefix only
                    }
                    return users;
                }
            } catch (Exception e) {
                // fall through
            }
        }
        // Fallback: users who accessed within 2× full-refill window
        long windowMs = (long) (config.getTokenBucket().getCapacity()
                                / config.getTokenBucket().getRefillRate() * 2_000L);
        long cutoff = System.currentTimeMillis() - windowMs;
        Set<String> users = new HashSet<>();
        fallbackAccess.forEach((uid, ts) -> { if (ts > cutoff) users.add(uid); });
        return users;
    }

    /**
     * Returns the remaining TTL (seconds) for a user's bucket.
     *
     * Bug-fix: previous code checked TTL on "rate_limit:{userId}:tokens" which
     * does not exist — the actual Redis key is "rate_limit:{userId}" (a Hash).
     */
    public long getTtlSeconds(String userId) {
        if (!redisFailing) {
            try {
                Long ttl = redisTemplate.getExpire(KEY_PREFIX + userId); // ← fixed key
                if (ttl != null) return ttl;
            } catch (Exception e) {
                // fall through
            }
        }
        // Fallback: estimate from last access time
        Long accessed = fallbackAccess.get(userId);
        if (accessed == null) return -1;
        long windowMs = (long) (config.getTokenBucket().getCapacity()
                                / config.getTokenBucket().getRefillRate() * 2_000L);
        return Math.max(0, (windowMs - (System.currentTimeMillis() - accessed)) / 1000);
    }

    public void reset(String userId) {
        if (redisTemplate != null) {
            try { redisTemplate.delete(KEY_PREFIX + userId); } catch (Exception ignored) {}
        }
        fallbackBuckets.remove(userId);
        fallbackAccess.remove(userId);
    }

    public void resetAll() {
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
                if (keys != null) keys.forEach(redisTemplate::delete);
            } catch (Exception ignored) {}
        }
        fallbackBuckets.clear();
        fallbackAccess.clear();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private boolean consumeFallback(String userId, long tokens) {
        TokenBucket bucket = fallbackBuckets.computeIfAbsent(userId,
                k -> new TokenBucket(config.getTokenBucket().getCapacity(),
                                     config.getTokenBucket().getRefillRate()));
        fallbackAccess.put(userId, System.currentTimeMillis());
        boolean allowed = bucket.tryConsume(tokens);
        logger.debug("Fallback rate-limit: user={} allowed={} remaining={}",
                userId, allowed, (long) bucket.getAvailableTokens());
        return allowed;
    }
}
