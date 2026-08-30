package com.sahil.linkedinapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahil.linkedinapi.api.error.ErrorCode;
import com.sahil.linkedinapi.api.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbound token bucket, keyed by API key when present and by client address otherwise.
 *
 * <p>This protects the LinkedIn session as much as it protects the service: one
 * enthusiastic caller in a loop is exactly how an account gets checkpointed.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final List<String> EXEMPT_PREFIXES = List.of(
            "/actuator/health", "/actuator/info", "/docs", "/swagger-ui", "/v3/api-docs", "/favicon.ico");

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final double refillPerSecond;
    private final int capacity;
    private final ObjectMapper mapper;

    public RateLimitFilter(AppProperties.RateLimit settings, ObjectMapper mapper) {
        this.refillPerSecond = Math.max(settings.requestsPerMinute(), 1) / 60.0;
        this.capacity = Math.max(settings.burst(), 1);
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(identity(request), k -> new Bucket(capacity));
        if (!bucket.tryConsume(refillPerSecond, capacity)) {
            long retryAfter = Math.max(1, Math.round(1.0 / refillPerSecond));
            var code = ErrorCode.RATE_LIMITED;
            response.setStatus(code.status().value());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                    code,
                    "Rate limit exceeded. Retry in %d second(s).".formatted(retryAfter),
                    MDC.get(RequestIdFilter.MDC_KEY)));
            return;
        }
        chain.doFilter(request, response);
    }

    private String identity(HttpServletRequest request) {
        String key = request.getHeader("X-API-Key");
        if (key != null && !key.isBlank()) {
            // Never use the raw secret as a map key — hash it.
            return "key:" + Integer.toHexString(key.hashCode());
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return "ip:" + forwarded.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** Classic token bucket. Synchronized because contention here is trivially low. */
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(double refillPerSecond, int capacity) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
