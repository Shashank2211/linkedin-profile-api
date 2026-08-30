package com.sahil.linkedinapi.api.error;

import com.sahil.linkedinapi.config.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/** Single place that turns anything thrown into the one error shape. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        // Expected conditions are info, not error — a private profile is not a bug.
        if (ex.code().status().is5xxServerError()) {
            log.error("{} — {}", ex.code(), ex.getMessage(), ex);
        } else {
            log.info("{} — {}", ex.code(), ex.getMessage());
        }
        var builder = ResponseEntity.status(ex.code().status());
        if (ex.retryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(ErrorResponse.of(ex.code(), ex.getMessage(), requestId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        var code = ErrorCode.INVALID_PROFILE_URL;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code,
                        "Missing required query parameter '" + ex.getParameterName() + "'.",
                        requestId()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex) {
        var code = ErrorCode.PROFILE_NOT_FOUND;
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, "No such endpoint: " + ex.getRequestURL(), requestId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        var code = ErrorCode.INTERNAL_ERROR;
        // Deliberately generic: internal messages can carry cookie fragments or
        // upstream URLs, and neither belongs in a client-visible body.
        return ResponseEntity.status(code.status())
                .body(ErrorResponse.of(code, "An unexpected internal error occurred.", requestId()));
    }

    private String requestId() {
        return MDC.get(RequestIdFilter.MDC_KEY);
    }
}
