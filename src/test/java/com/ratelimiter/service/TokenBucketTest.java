package com.ratelimiter.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the TokenBucket class.
 */
class TokenBucketTest {

    private TokenBucket tokenBucket;

    @BeforeEach
    void setUp() {
        tokenBucket = new TokenBucket(10, 5.0); // capacity: 10, refill rate: 5 tokens/sec
    }

    @Test
    void testConstructorWithValidParameters() {
        TokenBucket bucket = new TokenBucket(100, 10.0);
        assertEquals(100, bucket.getCapacity());
        assertEquals(10.0, bucket.getRefillRate());
        assertEquals(100, bucket.getAvailableTokens());
    }

    @Test
    void testConstructorWithInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 10.0));
    }

    @Test
    void testConstructorWithInvalidRefillRate() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, -1.0));
    }

    @Test
    void testTryConsumeWithValidTokens() {
        assertTrue(tokenBucket.tryConsume(5));
        assertTrue(tokenBucket.getAvailableTokens() >= 4.9 && tokenBucket.getAvailableTokens() <= 5.0);
    }

    @Test
    void testTryConsumeWithInvalidTokens() {
        assertThrows(IllegalArgumentException.class, () -> tokenBucket.tryConsume(0));
        assertThrows(IllegalArgumentException.class, () -> tokenBucket.tryConsume(-1));
    }

    @Test
    void testTryConsumeSingleToken() {
        assertTrue(tokenBucket.tryConsume());
        assertTrue(tokenBucket.getAvailableTokens() >= 8.9 && tokenBucket.getAvailableTokens() <= 9.0);
    }

    @Test
    void testTryConsumeWhenInsufficientTokens() {
        assertTrue(tokenBucket.tryConsume(10)); // consume all tokens
        assertFalse(tokenBucket.tryConsume(1)); // should fail
    }

    @Test
    void testRefillTokens() throws InterruptedException {
        tokenBucket.tryConsume(10); // consume all tokens
        assertEquals(0, tokenBucket.getAvailableTokens());

        Thread.sleep(1000); // wait 1 second

        // Should have refilled approximately 5 tokens (5 tokens/sec * 1 sec)
        double available = tokenBucket.getAvailableTokens();
        assertTrue(available >= 4.5 && available <= 5.5,
                "Expected ~5 tokens after 1 second, got " + available);
    }

    @Test
    void testRefillDoesNotExceedCapacity() throws InterruptedException {
        assertEquals(10, tokenBucket.getCapacity());
        assertEquals(10, tokenBucket.getAvailableTokens());

        Thread.sleep(1000); // wait 1 second

        // Tokens should not exceed capacity
        assertTrue(tokenBucket.getAvailableTokens() <= 10);
    }

    @Test
    void testReset() {
        tokenBucket.tryConsume(5);
        assertTrue(tokenBucket.getAvailableTokens() < 10);

        tokenBucket.reset();
        assertEquals(10, tokenBucket.getAvailableTokens());
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        TokenBucket sharedBucket = new TokenBucket(1000, 100.0);
        int threadCount = 10;
        int attemptsPerThread = 100;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successfulConsumptions = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < attemptsPerThread; j++) {
                        if (sharedBucket.tryConsume(1)) {
                            successfulConsumptions.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // At most, we should have consumed the initial capacity
        // Some threads will succeed, some will fail due to rate limiting
        assertTrue(successfulConsumptions.get() <= 1000,
                "Consumed more tokens than capacity: " + successfulConsumptions.get());
    }

    @Test
    void testConcurrentConsumptionAccuracy() throws InterruptedException {
        TokenBucket sharedBucket = new TokenBucket(100, 10.0);
        int threadCount = 5;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successfulConsumptions = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 30; j++) {
                        if (sharedBucket.tryConsume(1)) {
                            successfulConsumptions.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Total successful consumptions + remaining tokens should equal capacity
        double remaining = sharedBucket.getAvailableTokens();
        double total = successfulConsumptions.get() + remaining;

        assertTrue(total >= 99 && total <= 101,
                "Token accounting error. Total: " + total);
    }

    @Test
    void testGetters() {
        assertEquals(10, tokenBucket.getCapacity());
        assertEquals(5.0, tokenBucket.getRefillRate());
    }

    @Test
    void testPartialRefill() throws InterruptedException {
        tokenBucket.tryConsume(10); // empty the bucket

        Thread.sleep(500); // wait 0.5 seconds

        // Should have ~2.5 tokens (5 tokens/sec * 0.5 sec)
        double available = tokenBucket.getAvailableTokens();
        assertTrue(available >= 2.0 && available <= 3.0,
                "Expected ~2.5 tokens after 0.5 seconds, got " + available);
    }

    @Test
    void testMultipleRefills() throws InterruptedException {
        tokenBucket.tryConsume(8); // consume 8 tokens, 2 remaining

        Thread.sleep(500); // wait 0.5 seconds, should add ~2.5 tokens
        double afterFirstWait = tokenBucket.getAvailableTokens();
        assertTrue(afterFirstWait >= 4.0 && afterFirstWait <= 5.0);

        Thread.sleep(1000); // wait another 1 second, should add ~5 tokens
        double afterSecondWait = tokenBucket.getAvailableTokens();
        assertTrue(afterSecondWait >= 9.0 && afterSecondWait <= 10.0);
    }
}
