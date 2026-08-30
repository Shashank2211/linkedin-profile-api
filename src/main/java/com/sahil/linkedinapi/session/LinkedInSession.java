package com.sahil.linkedinapi.session;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One LinkedIn browser session, modelled as an object with health rather than as two
 * config strings.
 *
 * <p>The reason it is an object: the interesting states are not "set" and "unset" but
 * "working", "cooling down after a 429", and "quarantined because LinkedIn served a
 * checkpoint". A service that cannot represent those keeps hammering a dead cookie and
 * turns a recoverable rate-limit into a locked account.
 *
 * <p>Only one session is configured today. The shape is a pool because that is the
 * documented scale-out path, and retrofitting it later would touch every call site.
 */
public final class LinkedInSession {

    private final String id;
    private final String liAt;
    private final String csrfToken;

    private volatile Instant cooldownUntil = Instant.EPOCH;
    private volatile Instant lastUsed = Instant.EPOCH;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public LinkedInSession(String id, String liAt, String jsessionid) {
        this.id = id;
        this.liAt = liAt.trim();
        // The csrf-token header is the JSESSIONID value with its surrounding quotes
        // stripped. Callers paste it either way, so normalize both here.
        this.csrfToken = jsessionid.trim().replaceAll("^\"|\"$", "");
    }

    public String id() {
        return id;
    }

    public String csrfToken() {
        return csrfToken;
    }

    /** Exactly the two cookies Voyager needs. Never logged. */
    public String cookieHeader() {
        return "li_at=" + liAt + "; JSESSIONID=\"" + csrfToken + "\"";
    }

    public boolean available(Instant now) {
        return now.isAfter(cooldownUntil);
    }

    public void markUsed(Instant now) {
        this.lastUsed = now;
    }

    public Instant lastUsed() {
        return lastUsed;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        cooldownUntil = Instant.EPOCH;
    }

    /** @return true when this failure pushed the session into cooldown. */
    public boolean recordFailure(int maxConsecutive, Duration cooldown) {
        if (consecutiveFailures.incrementAndGet() >= maxConsecutive) {
            cooldownUntil = Instant.now().plus(cooldown);
            return true;
        }
        return false;
    }

    /** Immediate quarantine — for a checkpoint, a challenge, or an explicit 429. */
    public void quarantine(Duration duration) {
        cooldownUntil = Instant.now().plus(duration);
    }

    public Instant cooldownUntil() {
        return cooldownUntil;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
