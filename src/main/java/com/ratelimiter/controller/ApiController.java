package com.ratelimiter.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple REST API controller for testing rate limiting.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    /**
     * Test endpoint that returns a simple JSON response.
     * This endpoint is protected by the rate limiter filter.
     *
     * @return ResponseEntity with success message and timestamp
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Request successful!");
        response.put("timestamp", Instant.now().toString());
        response.put("status", "OK");

        return ResponseEntity.ok(response);
    }
}
