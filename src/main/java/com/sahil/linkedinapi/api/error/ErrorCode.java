package com.sahil.linkedinapi.api.error;

import org.springframework.http.HttpStatus;

/**
 * The complete set of failures a caller can see, each pinned to one status.
 *
 * <p>The distinction that matters: a profile that exists but is walled off is a
 * {@code 422}, not a {@code 500}. The request was well-formed and we understood it —
 * we just could not see the data. Collapsing that into a server error is the single
 * most common way a scraping API lies to its callers.
 */
public enum ErrorCode {

    INVALID_PROFILE_URL(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND),
    PROFILE_NOT_ACCESSIBLE(HttpStatus.UNPROCESSABLE_ENTITY),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
