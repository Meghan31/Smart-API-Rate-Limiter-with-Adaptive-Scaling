package com.ratelimiter.controller;

import com.ratelimiter.service.RedisRateLimiterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitoring Controller for Smart API Rate Limiter.
 *
 * Provides comprehensive monitoring endpoints for observing system health,
 * performance metrics, and rate limiting statistics in real-time.
 *
 * @author Smart Rate Limiter Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    @Autowired
    private RedisRateLimiterService rateLimiterService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // In-memory statistics tracking
    private static final AtomicLong totalRequests = new AtomicLong(0);
    private static final AtomicLong rateLimitedRequests = new AtomicLong(0);
    private static final AtomicLong successfulRequests = new AtomicLong(0);
    private static final long startTime = System.currentTimeMillis();

    /**
     * Increments the total request counter.
     * Called by the filter for every incoming request.
     */
    public static void incrementTotalRequests() {
        totalRequests.incrementAndGet();
    }

    /**
     * Increments the rate limited request counter.
     * Called when a request receives a 429 response.
     */
    public static void incrementRateLimitedRequests() {
        rateLimitedRequests.incrementAndGet();
    }

    /**
     * Increments the successful request counter.
     * Called when a request receives a 200 response.
     */
    public static void incrementSuccessfulRequests() {
        successfulRequests.incrementAndGet();
    }

    /**
     * GET /api/monitoring/stats
     *
     * Returns current rate limiter statistics including total requests,
     * rate limited requests, success rate, and active users.
     *
     * @return ResponseEntity containing statistics map
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();

        long total = totalRequests.get();
        long rateLimited = rateLimitedRequests.get();
        long successful = successfulRequests.get();

        // Calculate uptime
        long uptimeMs = System.currentTimeMillis() - startTime;
        long uptimeSeconds = uptimeMs / 1000;
        long uptimeMinutes = uptimeSeconds / 60;
        long uptimeHours = uptimeMinutes / 60;

        // Calculate success rate
        double successRate = total > 0 ? (successful * 100.0 / total) : 0.0;

        // Get active users from Redis
        Set<String> activeUsers = getActiveUsers();

        // Calculate average tokens remaining (sample from active users)
        double avgTokensRemaining = calculateAverageTokensRemaining(activeUsers);

        // Calculate requests per second
        double requestsPerSecond = uptimeSeconds > 0 ? (total * 1.0 / uptimeSeconds) : 0.0;

        stats.put("totalRequests", total);
        stats.put("successfulRequests", successful);
        stats.put("rateLimitedRequests", rateLimited);
        stats.put("successRate", String.format("%.2f%%", successRate));
        stats.put("activeUsers", activeUsers.size());
        stats.put("averageTokensRemaining", String.format("%.2f", avgTokensRemaining));
        stats.put("requestsPerSecond", String.format("%.2f", requestsPerSecond));
        stats.put("uptime", String.format("%dh %dm %ds",
            uptimeHours, uptimeMinutes % 60, uptimeSeconds % 60));
        stats.put("uptimeMillis", uptimeMs);
        stats.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(stats);
    }

    /**
     * GET /api/monitoring/health-detailed
     *
     * Returns detailed health information including Redis connectivity,
     * memory usage, and system metrics.
     *
     * @return ResponseEntity containing detailed health information
     */
    @GetMapping("/health-detailed")
    public ResponseEntity<Map<String, Object>> getDetailedHealth() {
        Map<String, Object> health = new HashMap<>();

        try {
            // Application health
            health.put("status", "UP");
            health.put("timestamp", Instant.now().toString());

            // Redis health
            Map<String, Object> redisHealth = checkRedisHealth();
            health.put("redis", redisHealth);

            // JVM memory
            Map<String, Object> memoryInfo = getMemoryInfo();
            health.put("memory", memoryInfo);

            // System info
            Map<String, Object> systemInfo = getSystemInfo();
            health.put("system", systemInfo);

            // Rate limiter health
            Map<String, Object> rateLimiterHealth = getRateLimiterHealth();
            health.put("rateLimiter", rateLimiterHealth);

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(health);
        }
    }

    /**
     * GET /api/monitoring/users
     *
     * Returns a list of active users with their current token status,
     * including remaining tokens and last access time.
     *
     * @return ResponseEntity containing list of user information
     */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> getActiveUsersInfo() {
        Map<String, Object> response = new HashMap<>();
        List<Map<String, Object>> usersList = new ArrayList<>();

        Set<String> activeUsers = getActiveUsers();

        for (String apiKey : activeUsers) {
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("apiKey", maskApiKey(apiKey));

            try {
                // Get remaining tokens
                long remainingTokens = rateLimiterService.getAvailableTokens(apiKey);
                userInfo.put("remainingTokens", remainingTokens);

                // Get TTL via service (works for both Redis and in-memory fallback)
                long ttl = rateLimiterService.getTtlSeconds(apiKey);
                userInfo.put("ttlSeconds", ttl);

                // Calculate percentage
                long capacity = rateLimiterService.getCapacity();
                double percentage = (remainingTokens * 100.0) / capacity;
                userInfo.put("capacityPercentage", String.format("%.2f%%", percentage));

                // Status indicator
                String status;
                if (remainingTokens > 50) {
                    status = "HEALTHY";
                } else if (remainingTokens > 10) {
                    status = "WARNING";
                } else {
                    status = "CRITICAL";
                }
                userInfo.put("status", status);

            } catch (Exception e) {
                userInfo.put("error", "Unable to fetch token info");
                userInfo.put("status", "UNKNOWN");
            }

            usersList.add(userInfo);
        }

        response.put("activeUsers", usersList.size());
        response.put("users", usersList);
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/monitoring/metrics
     *
     * Returns detailed metrics for performance monitoring and alerting.
     *
     * @return ResponseEntity containing detailed metrics
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        long total = totalRequests.get();
        long rateLimited = rateLimitedRequests.get();
        long successful = successfulRequests.get();
        long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;

        // Request metrics
        Map<String, Object> requestMetrics = new HashMap<>();
        requestMetrics.put("total", total);
        requestMetrics.put("successful", successful);
        requestMetrics.put("rateLimited", rateLimited);
        requestMetrics.put("failed", total - successful - rateLimited);
        requestMetrics.put("throughput", uptimeSeconds > 0 ? (total * 1.0 / uptimeSeconds) : 0.0);
        metrics.put("requests", requestMetrics);

        // Rate limiting metrics
        Map<String, Object> rateLimitMetrics = new HashMap<>();
        rateLimitMetrics.put("totalRateLimited", rateLimited);
        rateLimitMetrics.put("rateLimitRate", total > 0 ? (rateLimited * 100.0 / total) : 0.0);
        rateLimitMetrics.put("activeUsers", getActiveUsers().size());
        metrics.put("rateLimiting", rateLimitMetrics);

        // Performance metrics
        Map<String, Object> performanceMetrics = new HashMap<>();
        performanceMetrics.put("uptimeSeconds", uptimeSeconds);
        performanceMetrics.put("averageRequestsPerSecond", uptimeSeconds > 0 ? (total * 1.0 / uptimeSeconds) : 0.0);
        metrics.put("performance", performanceMetrics);

        metrics.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(metrics);
    }

    /**
     * GET /api/monitoring/reset
     *
     * Resets the monitoring statistics (for testing purposes).
     * In production, this should be secured or removed.
     *
     * @return ResponseEntity with reset confirmation
     */
    @GetMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetStats() {
        totalRequests.set(0);
        rateLimitedRequests.set(0);
        successfulRequests.set(0);

        Map<String, Object> response = new HashMap<>();
        response.put("status", "reset");
        response.put("message", "Monitoring statistics have been reset");
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }

    // ========== Private Helper Methods ==========

    /**
     * Retrieves all active API keys — delegates to the service which handles
     * both Redis (correct key pattern) and in-memory fallback.
     */
    private Set<String> getActiveUsers() {
        return rateLimiterService.getActiveUsers();
    }

    /**
     * Calculates the average number of tokens remaining across all active users.
     */
    private double calculateAverageTokensRemaining(Set<String> activeUsers) {
        if (activeUsers.isEmpty()) {
            return 0.0;
        }

        long totalTokens = 0;
        int validUsers = 0;

        for (String apiKey : activeUsers) {
            try {
                long tokens = rateLimiterService.getAvailableTokens(apiKey);
                totalTokens += tokens;
                validUsers++;
            } catch (Exception e) {
                // Skip users with errors
            }
        }

        return validUsers > 0 ? (totalTokens * 1.0 / validUsers) : 0.0;
    }

    /**
     * Masks an API key for secure display (shows only first and last 4 characters).
     */
    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        int length = apiKey.length();
        return apiKey.substring(0, 4) + "****" + apiKey.substring(length - 4);
    }

    /**
     * Checks Redis connectivity and performance.
     */
    private Map<String, Object> checkRedisHealth() {
        Map<String, Object> redisHealth = new HashMap<>();
        try {
            long startTime = System.nanoTime();
            redisTemplate.execute((RedisConnection connection) -> {
                return connection.ping();
            });
            long latency = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

            redisHealth.put("status", "UP");
            redisHealth.put("latencyMs", latency);

            // Get Redis info (basic info only to avoid deprecated methods)
            try {
                Properties info = redisTemplate.execute((RedisConnection connection) -> {
                    return connection.serverCommands().info();
                });

                if (info != null) {
                    redisHealth.put("usedMemory", info.getProperty("used_memory_human", "N/A"));
                    redisHealth.put("connectedClients", info.getProperty("connected_clients", "N/A"));
                    redisHealth.put("version", info.getProperty("redis_version", "N/A"));
                }
            } catch (Exception e) {
                // Info command not critical, just skip
                redisHealth.put("info", "unavailable");
            }

        } catch (Exception e) {
            redisHealth.put("status", "DOWN");
            redisHealth.put("error", e.getMessage());
        }
        return redisHealth;
    }

    /**
     * Retrieves JVM memory information.
     */
    private Map<String, Object> getMemoryInfo() {
        Map<String, Object> memoryInfo = new HashMap<>();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long heapCommitted = memoryBean.getHeapMemoryUsage().getCommitted();

        memoryInfo.put("heapUsedMB", heapUsed / (1024 * 1024));
        memoryInfo.put("heapMaxMB", heapMax / (1024 * 1024));
        memoryInfo.put("heapCommittedMB", heapCommitted / (1024 * 1024));
        memoryInfo.put("heapUsagePercent", String.format("%.2f%%", (heapUsed * 100.0 / heapMax)));

        return memoryInfo;
    }

    /**
     * Retrieves system information.
     */
    private Map<String, Object> getSystemInfo() {
        Map<String, Object> systemInfo = new HashMap<>();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        systemInfo.put("availableProcessors", osBean.getAvailableProcessors());
        systemInfo.put("systemLoadAverage", String.format("%.2f", osBean.getSystemLoadAverage()));
        systemInfo.put("osName", osBean.getName());
        systemInfo.put("osVersion", osBean.getVersion());

        return systemInfo;
    }

    /**
     * Retrieves rate limiter health status.
     */
    private Map<String, Object> getRateLimiterHealth() {
        Map<String, Object> health = new HashMap<>();

        long total = totalRequests.get();
        long rateLimited = rateLimitedRequests.get();
        double rateLimitRate = total > 0 ? (rateLimited * 100.0 / total) : 0.0;

        health.put("status", "OPERATIONAL");
        health.put("totalRequests", total);
        health.put("rateLimitedRequests", rateLimited);
        health.put("rateLimitRate", String.format("%.2f%%", rateLimitRate));
        health.put("activeUsers", getActiveUsers().size());

        return health;
    }
}
