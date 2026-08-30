package com.sahil.linkedinapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * All tuning lives here and every value is overridable by an environment variable,
 * so a running deployment can be re-tuned — or a broken Voyager decoration id
 * replaced — without a rebuild.
 */
@ConfigurationProperties(prefix = "linkedin-api")
public record AppProperties(

        /* Comma-separated accepted API keys. Empty disables auth (dev only). */
        @DefaultValue("") String apiKeys,

        /* Total wall-clock budget for one inbound request, spent down by the source chain. */
        @DefaultValue("PT12S") Duration requestBudget,

        @DefaultValue RateLimit rateLimit,
        @DefaultValue CacheSettings cache,
        @DefaultValue Breaker breaker,
        @DefaultValue Session session,
        @DefaultValue Http http,
        @DefaultValue Sources sources
) {

    public record RateLimit(
            @DefaultValue("30") int requestsPerMinute,
            @DefaultValue("10") int burst
    ) {
    }

    /**
     * Two TTLs, not one. Inside {@code freshTtl} we serve from cache without asking
     * LinkedIn anything. Between fresh and stale we try to refresh but fall back to the
     * cached copy if the upstream is angry — which is what keeps a demo alive.
     */
    public record CacheSettings(
            @DefaultValue("PT6H") Duration freshTtl,
            @DefaultValue("PT24H") Duration staleTtl,
            @DefaultValue("5000") int maxEntries
    ) {
    }

    public record Breaker(
            @DefaultValue("3") int failureThreshold,
            @DefaultValue("PT2M") Duration openDuration
    ) {
    }

    public record Session(
            @DefaultValue("") String liAt,
            @DefaultValue("") String jsessionid,
            @DefaultValue("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36") String userAgent,
            @DefaultValue("en-US,en;q=0.9") String acceptLanguage,
            @DefaultValue("PT15M") Duration cooldown,
            @DefaultValue("3") int maxConsecutiveFailures
    ) {
        public boolean configured() {
            return liAt != null && !liAt.isBlank() && jsessionid != null && !jsessionid.isBlank();
        }
    }

    public record Http(
            @DefaultValue("") String proxy,
            @DefaultValue("PT5S") Duration connectTimeout
    ) {
    }

    public record Sources(
            @DefaultValue SourceSettings voyager,
            @DefaultValue SourceSettings publicHtml
    ) {
    }

    public record SourceSettings(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("https://www.linkedin.com") String baseUrl,
            @DefaultValue("com.linkedin.voyager.dash.deco.identity.profile.FullProfileWithEntities-93")
            String decorationId,
            @DefaultValue("PT10S") Duration timeout,
            /* Politeness floor between two outbound calls from this source. */
            @DefaultValue("PT5S") Duration minInterval,
            @DefaultValue("PT2S") Duration jitter
    ) {
    }

    public List<String> apiKeyList() {
        if (apiKeys == null || apiKeys.isBlank()) {
            return List.of();
        }
        return Arrays.stream(apiKeys.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
