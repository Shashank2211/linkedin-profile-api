package com.sahil.linkedinapi.api.error;

import java.time.Instant;

/**
 * One error shape for every failure, so a client writes one error branch.
 * {@code requestId} is the same value returned in the {@code X-Request-Id} header
 * and stamped on every log line for the request.
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, String requestId, Instant timestamp) {
    }

    public static ErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ErrorResponse(new Body(code.name(), message, requestId, Instant.now()));
    }
}
