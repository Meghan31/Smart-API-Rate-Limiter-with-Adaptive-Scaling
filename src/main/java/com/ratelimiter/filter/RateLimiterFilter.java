package com.ratelimiter.filter;

import com.ratelimiter.config.RateLimiterConfig;
import com.ratelimiter.service.RedisRateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that applies distributed rate limiting to all /api/* requests using Redis-based Token Bucket algorithm.
 *
 * This filter uses Redis to store rate limiting state, allowing multiple application instances
 * to share the same rate limiting data. Each user is identified by their X-API-Key header,
 * or defaults to "anonymous" if no key is provided.
 *
 * For each request, the filter:
 * 1. Extracts user identifier from X-API-Key header (or uses "anonymous")
 * 2. Attempts to consume one token from the user's Redis-backed token bucket
 * 3. If successful, the request proceeds
 * 4. If no tokens available, returns HTTP 429 (Too Many Requests)
 *
 * The filter adds the following headers to all responses:
 * - X-RateLimit-Limit: The maximum number of tokens (bucket capacity)
 * - X-RateLimit-Remaining: The number of tokens currently available for this user
 * - X-RateLimit-User: The user identifier used for rate limiting
 */
@Component
@Order(1)
public class RateLimiterFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiterFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String ANONYMOUS_USER = "anonymous";

    private final RedisRateLimiterService rateLimiterService;
    private final RateLimiterConfig config;

    public RateLimiterFilter(RedisRateLimiterService rateLimiterService, RateLimiterConfig config) {
        this.rateLimiterService = rateLimiterService;
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Only apply rate limiting to /api/* endpoints
        String requestPath = request.getRequestURI();
        if (!requestPath.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skip rate limiting if disabled
        if (!config.getTokenBucket().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract user identifier from X-API-Key header, or use "anonymous"
        String userId = extractUserId(request);

        // Try to consume a token for this user
        boolean allowed = rateLimiterService.tryConsume(userId);

        // Get current state for headers
        long limit = rateLimiterService.getCapacity();
        long remaining = rateLimiterService.getAvailableTokens(userId);

        // Add rate limit headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-User", userId);

        if (allowed) {
            // Allow the request to proceed
            logger.debug("Request allowed for user: {} on path: {}", userId, requestPath);
            filterChain.doFilter(request, response);
        } else {
            // Rate limit exceeded - return 429 Too Many Requests
            logger.info("Rate limit exceeded for user: {} on path: {}", userId, requestPath);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests. Please try again later.\",\"limit\":%d,\"remaining\":%d,\"user\":\"%s\"}",
                limit,
                remaining,
                userId
            ));
            response.getWriter().flush();
        }
    }

    /**
     * Extracts user identifier from the request.
     * First checks for X-API-Key header, falls back to "anonymous" if not present.
     *
     * @param request The HTTP request
     * @return The user identifier
     */
    private String extractUserId(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey.trim();
        }
        return ANONYMOUS_USER;
    }
}
