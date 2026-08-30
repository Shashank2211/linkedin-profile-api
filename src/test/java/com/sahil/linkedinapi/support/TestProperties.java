package com.sahil.linkedinapi.support;

import com.sahil.linkedinapi.config.AppProperties;

import java.time.Duration;

/**
 * Hand-built {@link AppProperties} for unit tests that construct collaborators directly
 * rather than booting Spring.
 *
 * <p>Pacing intervals are zero here on purpose. {@code PaceGate} is deliberately slow in
 * production — that is its whole job — and a test suite that honours a six-second gap
 * between calls is a test suite nobody runs.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static AppProperties defaults() {
        return withCacheTtls(Duration.ofHours(6), Duration.ofHours(24));
    }

    public static AppProperties withCacheTtls(Duration fresh, Duration stale) {
        return new AppProperties(
                "",
                Duration.ofSeconds(12),
                new AppProperties.RateLimit(600, 100),
                new AppProperties.CacheSettings(fresh, stale, 100),
                new AppProperties.Breaker(2, Duration.ofMinutes(2)),
                new AppProperties.Session("", "", "test-agent", "en-US",
                        Duration.ofMinutes(15), 3),
                new AppProperties.Http("", Duration.ofSeconds(5)),
                new AppProperties.Sources(sourceSettings(), sourceSettings()));
    }

    private static AppProperties.SourceSettings sourceSettings() {
        return new AppProperties.SourceSettings(
                true,
                "https://www.linkedin.com",
                "com.linkedin.voyager.dash.deco.identity.profile.FullProfileWithEntities-93",
                Duration.ofSeconds(10),
                Duration.ZERO,
                Duration.ZERO);
    }
}
