package com.ratelimiter.service;

import java.time.Instant;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Token Bucket implementation for rate limiting.
 *
 * The Token Bucket algorithm allows a certain number of requests (tokens) to be made
 * within a time period. Tokens are refilled at a constant rate.
 */
public class TokenBucket {

    private final long capacity;
    private final double refillRate; // tokens per second
    private double availableTokens;
    private Instant lastRefillTimestamp;
    private final Lock lock;

    /**
     * Creates a new TokenBucket with the specified capacity and refill rate.
     *
     * @param capacity The maximum number of tokens the bucket can hold
     * @param refillRate The rate at which tokens are added to the bucket (tokens per second)
     */
    public TokenBucket(long capacity, double refillRate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be greater than 0");
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be greater than 0");
        }

        this.capacity = capacity;
        this.refillRate = refillRate;
        this.availableTokens = capacity;
        this.lastRefillTimestamp = Instant.now();
        this.lock = new ReentrantLock();
    }

    /**
     * Attempts to consume the specified number of tokens from the bucket.
     *
     * @param tokens The number of tokens to consume
     * @return true if the tokens were successfully consumed, false otherwise
     */
    public boolean tryConsume(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("Tokens must be greater than 0");
        }

        lock.lock();
        try {
            refill();

            if (availableTokens >= tokens) {
                availableTokens -= tokens;
                return true;
            }

            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Attempts to consume a single token from the bucket.
     *
     * @return true if the token was successfully consumed, false otherwise
     */
    public boolean tryConsume() {
        return tryConsume(1);
    }

    /**
     * Refills the bucket based on the time elapsed since the last refill.
     * This method must be called while holding the lock.
     */
    private void refill() {
        Instant now = Instant.now();
        double elapsedSeconds = (now.toEpochMilli() - lastRefillTimestamp.toEpochMilli()) / 1000.0;

        if (elapsedSeconds > 0) {
            double tokensToAdd = elapsedSeconds * refillRate;
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }

    /**
     * Gets the current number of available tokens in the bucket.
     *
     * @return The number of available tokens
     */
    public double getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Gets the maximum capacity of the bucket.
     *
     * @return The bucket capacity
     */
    public long getCapacity() {
        return capacity;
    }

    /**
     * Gets the refill rate of the bucket.
     *
     * @return The refill rate in tokens per second
     */
    public double getRefillRate() {
        return refillRate;
    }

    /**
     * Resets the bucket to its initial state with full capacity.
     */
    public void reset() {
        lock.lock();
        try {
            availableTokens = capacity;
            lastRefillTimestamp = Instant.now();
        } finally {
            lock.unlock();
        }
    }
}
