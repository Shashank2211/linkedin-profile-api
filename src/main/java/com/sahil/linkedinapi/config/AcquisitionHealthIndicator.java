package com.sahil.linkedinapi.config;

import com.sahil.linkedinapi.acquisition.ProfileSourceChain;
import com.sahil.linkedinapi.cache.ProfileCache;
import com.sahil.linkedinapi.session.SessionManager;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the two things that actually determine whether this service can answer:
 * is a LinkedIn session usable, and are the source breakers closed.
 *
 * <p>Reports {@code UP} whenever <em>any</em> path can still serve — including
 * cache-only — because the platform health check should restart the container for a
 * broken app, not for an upstream that is refusing us. A dead cookie is an operator
 * problem, visible in the details, not a reason to loop the deployment.
 *
 * <p>Contains no secrets: session ids and availability flags only.
 */
@Component("acquisition")
public class AcquisitionHealthIndicator implements HealthIndicator {

    private final SessionManager sessions;
    private final ProfileSourceChain chain;
    private final ProfileCache cache;

    public AcquisitionHealthIndicator(SessionManager sessions, ProfileSourceChain chain,
                                      ProfileCache cache) {
        this.sessions = sessions;
        this.chain = chain;
        this.cache = cache;
    }

    @Override
    public Health health() {
        var statuses = sessions.status();
        boolean anySessionAvailable = statuses.stream().anyMatch(SessionManager.SessionStatus::available);
        boolean anySourceClosed = chain.breakerHealth().containsValue(Boolean.TRUE);

        return Health.up()
                .withDetail("sessionsConfigured", statuses.size())
                .withDetail("sessionAvailable", anySessionAvailable)
                .withDetail("sessions", statuses)
                .withDetail("sourceBreakersClosed", chain.breakerHealth())
                .withDetail("degraded", !(anySessionAvailable && anySourceClosed))
                .withDetail("cachedProfiles", cache.size())
                .withDetail("cacheHitRate", Math.round(cache.hitRate() * 100.0) / 100.0)
                .build();
    }
}
