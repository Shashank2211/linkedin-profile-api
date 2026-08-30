package com.sahil.linkedinapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahil.linkedinapi.api.error.ErrorCode;
import com.sahil.linkedinapi.api.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Accepts {@code X-API-Key: <key>} or {@code Authorization: Bearer <key>}.
 *
 * <p>Comparison is constant-time. It is a small thing on a hiring-challenge service,
 * but {@code String.equals} on a secret leaks its prefix through timing and there is
 * no reason to write the vulnerable version.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    /** Open paths: health for the platform probe, docs so a reviewer can explore. */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/actuator/health", "/actuator/info", "/docs", "/swagger-ui", "/v3/api-docs", "/favicon.ico");

    private final List<String> acceptedKeys;
    private final ObjectMapper mapper;

    public ApiKeyFilter(List<String> acceptedKeys, ObjectMapper mapper) {
        this.acceptedKeys = acceptedKeys;
        this.mapper = mapper;
        if (acceptedKeys.isEmpty()) {
            log.warn("=================================================================");
            log.warn(" API_KEYS is empty — the API is running UNAUTHENTICATED.");
            log.warn(" Acceptable for local development only. Set API_KEYS in deploy.");
            log.warn("=================================================================");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return acceptedKeys.isEmpty() || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = presentedKey(request);
        if (presented == null || !matches(presented)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private String presentedKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private boolean matches(String presented) {
        byte[] candidate = presented.getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        // Check every key, no early exit — keeps the work constant regardless of match.
        for (String accepted : acceptedKeys) {
            ok |= MessageDigest.isEqual(candidate, accepted.getBytes(StandardCharsets.UTF_8));
        }
        return ok;
    }

    private void reject(HttpServletResponse response) throws IOException {
        var code = ErrorCode.UNAUTHORIZED;
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                code,
                "Missing or invalid API key. Send it as 'X-API-Key' or 'Authorization: Bearer <key>'.",
                MDC.get(RequestIdFilter.MDC_KEY)));
    }
}
