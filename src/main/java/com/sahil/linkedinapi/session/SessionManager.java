package com.sahil.linkedinapi.session;

import com.sahil.linkedinapi.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Hands out sessions least-recently-used and records what happened to them.
 *
 * <p>Supports comma-separated {@code LINKEDIN_LI_AT} / {@code LINKEDIN_JSESSIONID}
 * values so a pool can be supplied without a code change, though the intended
 * deployment for this challenge is a single session with conservative pacing.
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final List<LinkedInSession> sessions;
    private final AppProperties.Session settings;

    public SessionManager(AppProperties props) {
        this.settings = props.session();
        this.sessions = buildSessions(settings);
        if (sessions.isEmpty()) {
            log.warn("No LinkedIn session configured (LINKEDIN_LI_AT / LINKEDIN_JSESSIONID are unset). "
                    + "The Voyager source is disabled; only the public-HTML fallback will run.");
        } else {
            log.info("Loaded {} LinkedIn session(s).", sessions.size());
        }
    }

    private static List<LinkedInSession> buildSessions(AppProperties.Session settings) {
        if (!settings.configured()) {
            return List.of();
        }
        String[] liAts = settings.liAt().split(",");
        String[] jsessions = settings.jsessionid().split(",");
        int count = Math.min(liAts.length, jsessions.length);
        return java.util.stream.IntStream.range(0, count)
                .filter(i -> !liAts[i].isBlank() && !jsessions[i].isBlank())
                .mapToObj(i -> new LinkedInSession("session-" + (i + 1), liAts[i], jsessions[i]))
                .toList();
    }

    public boolean hasSessions() {
        return !sessions.isEmpty();
    }

    /** Least-recently-used available session, if any. */
    public Optional<LinkedInSession> checkout() {
        Instant now = Instant.now();
        Optional<LinkedInSession> picked = sessions.stream()
                .filter(s -> s.available(now))
                .min(Comparator.comparing(LinkedInSession::lastUsed));
        picked.ifPresent(s -> s.markUsed(now));
        return picked;
    }

    public void recordSuccess(LinkedInSession session) {
        session.recordSuccess();
    }

    public void recordFailure(LinkedInSession session, String reason) {
        boolean cooled = session.recordFailure(settings.maxConsecutiveFailures(), settings.cooldown());
        if (cooled) {
            log.warn("Session {} cooling down until {} after {} consecutive failures ({})",
                    session.id(), session.cooldownUntil(), session.consecutiveFailures(), reason);
        }
    }

    /**
     * Immediate quarantine. Called for the responses that mean "stop, now": a checkpoint
     * or challenge redirect, a 401/403, or a 429. Retrying any of these is how a
     * temporary block becomes a permanent one.
     */
    public void quarantine(LinkedInSession session, String reason) {
        session.quarantine(settings.cooldown());
        log.warn("Session {} quarantined until {} — {}", session.id(), session.cooldownUntil(), reason);
    }

    /** Snapshot for the health endpoint. Contains no secrets. */
    public List<SessionStatus> status() {
        Instant now = Instant.now();
        return sessions.stream()
                .map(s -> new SessionStatus(s.id(), s.available(now), s.consecutiveFailures(),
                        s.available(now) ? null : s.cooldownUntil()))
                .toList();
    }

    public record SessionStatus(String id, boolean available, int consecutiveFailures, Instant cooldownUntil) {
    }
}
