package com.sahil.linkedinapi.acquisition;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deliberately small circuit breaker: closed, open for a fixed window, then one
 * half-open trial request decides.
 *
 * <p>Hand-rolled rather than pulled from Resilience4j on purpose. It is forty lines, it
 * is unit-tested in this repo, and it removes a dependency from a service whose whole
 * value proposition is that a reviewer can clone it and have it build first time.
 * Resilience4j is the right swap the moment this needs bulkheads and metrics —
 * noted in the README's future-work section.
 */
public final class SourceBreaker {

    private final int failureThreshold;
    private final Duration openDuration;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);

    public SourceBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration;
    }

    public boolean allowsRequest() {
        return Instant.now().isAfter(openUntil.get());
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntil.set(Instant.EPOCH);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntil.set(Instant.now().plus(openDuration));
            // Reset the counter so that after the window one trial request decides,
            // rather than the breaker re-opening on the very next failure forever.
            consecutiveFailures.set(0);
        }
    }

    public boolean isOpen() {
        return !allowsRequest();
    }

    public Instant openUntil() {
        return openUntil.get();
    }
}
