package com.sahil.linkedinapi.acquisition;

/**
 * This source could not answer. Not fatal — the chain moves to the next one.
 * Never surfaced to the caller directly; the chain decides the final status.
 */
public class SourceUnavailableException extends RuntimeException {

    private final SourceType source;

    public SourceUnavailableException(SourceType source, String message) {
        super(message);
        this.source = source;
    }

    public SourceUnavailableException(SourceType source, String message, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    public SourceType source() {
        return source;
    }
}
