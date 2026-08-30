package com.sahil.linkedinapi.api.error;

/** Base class for everything the caller is allowed to see the message of. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Long retryAfterSeconds;

    public ApiException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ApiException(ErrorCode code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public ApiException(ErrorCode code, String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
