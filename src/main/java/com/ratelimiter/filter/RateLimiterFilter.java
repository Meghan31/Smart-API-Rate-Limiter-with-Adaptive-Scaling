package com.ratelimiter.filter;

import com.ratelimiter.config.RateLimiterConfig;
import com.ratelimiter.service.TokenBucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that applies rate limiting to all /api/* requests using a Token Bucket algorithm.
 *
 * For each request, the filter attempts to consume one token from the bucket.
 * If successful, the request proceeds. If no tokens are available, the filter
 * returns HTTP 429 (Too Many Requests).
 *
 * The filter adds the following headers to all responses:
 * - X-RateLimit-Limit: The maximum number of tokens (bucket capacity)
 * - X-RateLimit-Remaining: The number of tokens currently available
 */
@Component
@Order(1)
public class RateLimiterFilter extends OncePerRequestFilter {

    private final TokenBucket tokenBucket;
    private final RateLimiterConfig config;

    public RateLimiterFilter(TokenBucket tokenBucket, RateLimiterConfig config) {
        this.tokenBucket = tokenBucket;
        this.config = config;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Checking if the request is for /api/* endpoints
        String requestPath = request.getRequestURI();
        if (!requestPath.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skipping rate limiting if it is disabled
        if (!config.getTokenBucket().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Trying to consume a token from the bucket
        boolean allowed = tokenBucket.tryConsume();

        // Getting the current state of the bucket for headers
        long limit = tokenBucket.getCapacity();
        long remaining = (long) tokenBucket.getAvailableTokens();

        // Adding rate limit headers to the response
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

        if (allowed) {
            // Allowing the request to proceed
            filterChain.doFilter(request, response);
        } else {
            // Rate limit exceeded - return 429 Too Many Requests
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                "{\"error\":\"Rate limit exceeded\",\"message\":\"Too many requests. Please try again later.\",\"limit\":%d,\"remaining\":%d}",
                limit,
                remaining
            ));
            response.getWriter().flush();
        }
    }
}
