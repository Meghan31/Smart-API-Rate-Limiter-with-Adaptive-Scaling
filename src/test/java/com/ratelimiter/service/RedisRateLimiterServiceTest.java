package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RedisRateLimiterService.
 *
 * These tests require a running Redis instance.
 * Start Redis before running: docker-compose up -d
 */
@SpringBootTest
class RedisRateLimiterServiceTest {

    @Autowired
    private RedisRateLimiterService rateLimiterService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimiterConfig config;

    private static final String TEST_USER_1 = "test-user-1";
    private static final String TEST_USER_2 = "test-user-2";

    @BeforeEach
    void setUp() {
        // Clean up before each test
        rateLimiterService.reset(TEST_USER_1);
        rateLimiterService.reset(TEST_USER_2);
        rateLimiterService.reset("anonymous");
    }

    @Test
    void testBasicTokenConsumption() {
        // First request should succeed
        assertTrue(rateLimiterService.tryConsume(TEST_USER_1));

        // Check available tokens decreased
        long availableTokens = rateLimiterService.getAvailableTokens(TEST_USER_1);
        assertEquals(config.getTokenBucket().getCapacity() - 1, availableTokens);
    }

    @Test
    void testMultipleTokenConsumption() {
        long capacity = config.getTokenBucket().getCapacity();

        // Consume 5 tokens
        assertTrue(rateLimiterService.tryConsume(TEST_USER_1, 5));

        // Check available tokens
        long availableTokens = rateLimiterService.getAvailableTokens(TEST_USER_1);
        assertEquals(capacity - 5, availableTokens);
    }

    @Test
    void testRateLimitExceeded() {
        long capacity = config.getTokenBucket().getCapacity();

        // Consume all tokens
        for (int i = 0; i < capacity; i++) {
            assertTrue(rateLimiterService.tryConsume(TEST_USER_1),
                    "Request " + (i + 1) + " should succeed");
        }

        // Next request should fail
        assertFalse(rateLimiterService.tryConsume(TEST_USER_1),
                "Request should be rate limited after consuming all tokens");

        // Available tokens should be 0
        long availableTokens = rateLimiterService.getAvailableTokens(TEST_USER_1);
        assertEquals(0, availableTokens);
    }

    @Test
    void testTokenRefill() throws InterruptedException {
        long capacity = config.getTokenBucket().getCapacity();
        double refillRate = config.getTokenBucket().getRefillRate();

        // Consume all tokens
        for (int i = 0; i < capacity; i++) {
            assertTrue(rateLimiterService.tryConsume(TEST_USER_1));
        }

        // Should be rate limited
        assertFalse(rateLimiterService.tryConsume(TEST_USER_1));

        // Wait for refill (refillRate tokens per second)
        // Wait long enough for at least 1 token to be refilled
        long waitMillis = (long) ((1.0 / refillRate) * 1000 * 2); // Wait for 2 tokens worth of time
        Thread.sleep(waitMillis);

        // Should succeed now after refill
        assertTrue(rateLimiterService.tryConsume(TEST_USER_1),
                "Request should succeed after token refill");
    }

    @Test
    void testDifferentUsersHaveSeparateLimits() {
        // Consume all tokens for user 1
        long capacity = config.getTokenBucket().getCapacity();
        for (int i = 0; i < capacity; i++) {
            assertTrue(rateLimiterService.tryConsume(TEST_USER_1));
        }

        // User 1 should be rate limited
        assertFalse(rateLimiterService.tryConsume(TEST_USER_1));

        // User 2 should still be able to make requests
        assertTrue(rateLimiterService.tryConsume(TEST_USER_2),
                "User 2 should have independent rate limit from User 1");
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        int numThreads = 10;
        int requestsPerThread = 15;
        long capacity = config.getTokenBucket().getCapacity();

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);

        List<Boolean> results = new ArrayList<>();

        // Launch concurrent requests
        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    boolean result = rateLimiterService.tryConsume(TEST_USER_1);
                    synchronized (results) {
                        results.add(result);
                    }
                }
                latch.countDown();
            });
        }

        // Wait for all threads to complete
        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete");
        executor.shutdown();

        // Count successful and failed requests
        long successCount = results.stream().filter(r -> r).count();
        long failCount = results.stream().filter(r -> !r).count();

        // Total requests
        int totalRequests = numThreads * requestsPerThread;

        System.out.println("Total requests: " + totalRequests);
        System.out.println("Successful: " + successCount);
        System.out.println("Failed: " + failCount);
        System.out.println("Capacity: " + capacity);

        // Should have exactly 'capacity' successful requests
        // Due to concurrent access, the actual count might be around capacity
        assertTrue(successCount <= capacity,
                "Successful requests should not exceed capacity");
        assertTrue(successCount >= capacity * 0.9,
                "Should have at least 90% of capacity successful (accounting for race conditions)");
    }

    @Test
    void testReset() {
        long capacity = config.getTokenBucket().getCapacity();

        // Consume some tokens
        for (int i = 0; i < 50; i++) {
            assertTrue(rateLimiterService.tryConsume(TEST_USER_1));
        }

        // Available tokens should be reduced
        long tokensBeforeReset = rateLimiterService.getAvailableTokens(TEST_USER_1);
        assertTrue(tokensBeforeReset < capacity);

        // Reset
        rateLimiterService.reset(TEST_USER_1);

        // After reset, should have full capacity again
        long tokensAfterReset = rateLimiterService.getAvailableTokens(TEST_USER_1);
        assertEquals(capacity, tokensAfterReset);
    }

    @Test
    void testDisabledRateLimiting() {
        // Save original state
        boolean originalEnabled = config.getTokenBucket().isEnabled();

        try {
            // Disable rate limiting
            config.getTokenBucket().setEnabled(false);

            long capacity = config.getTokenBucket().getCapacity();

            // Try to consume more than capacity
            for (int i = 0; i < capacity + 50; i++) {
                assertTrue(rateLimiterService.tryConsume(TEST_USER_1),
                        "All requests should succeed when rate limiting is disabled");
            }
        } finally {
            // Restore original state
            config.getTokenBucket().setEnabled(originalEnabled);
        }
    }

    @Test
    void testGetCapacity() {
        long capacity = rateLimiterService.getCapacity();
        assertEquals(config.getTokenBucket().getCapacity(), capacity);
    }
}
