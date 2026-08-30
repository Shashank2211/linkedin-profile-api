package com.sahil.linkedinapi.acquisition;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enforces a minimum gap between two outbound calls from the same source, plus a random
 * jitter so the traffic does not arrive on a metronome.
 *
 * <p>This is the politeness control, and it is the reason the cache matters so much:
 * the service is deliberately slow at talking to LinkedIn, so almost every inbound
 * request has to be answered from memory.
 *
 * <p>Blocking here is fine and intended — the app runs on virtual threads, so a parked
 * request costs a continuation, not a platform thread.
 */
public final class PaceGate {

    private final Duration minInterval;
    private final Duration jitter;
    private final Object lock = new Object();
    private Instant nextAllowed = Instant.EPOCH;

    public PaceGate(Duration minInterval, Duration jitter) {
        this.minInterval = minInterval == null ? Duration.ZERO : minInterval;
        this.jitter = jitter == null ? Duration.ZERO : jitter;
    }

    /**
     * Waits until this source is allowed to make its next call.
     *
     * @param budget how long the caller can afford to wait
     * @return the budget left after waiting
     * @throws SourceUnavailableException if the wait would exceed the budget — better to
     *                                    fall through to the next source than to burn the
     *                                    whole request budget queueing
     */
    public Duration await(SourceType source, Duration budget) {
        Duration wait;
        synchronized (lock) {
            Instant now = Instant.now();
            wait = nextAllowed.isAfter(now) ? Duration.between(now, nextAllowed) : Duration.ZERO;
            if (wait.compareTo(budget) > 0) {
                throw new SourceUnavailableException(source,
                        "Pacing gate would hold this request for " + wait.toMillis()
                                + "ms, over the remaining budget of " + budget.toMillis() + "ms.");
            }
            long jitterMillis = jitter.isZero() ? 0
                    : ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1);
            nextAllowed = now.plus(wait).plus(minInterval).plusMillis(jitterMillis);
        }
        if (!wait.isZero()) {
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceUnavailableException(source, "Interrupted while pacing.", e);
            }
        }
        return budget.minus(wait);
    }
}
