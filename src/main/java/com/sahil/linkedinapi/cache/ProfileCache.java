package com.sahil.linkedinapi.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.domain.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * In-process cache with two lifetimes.
 *
 * <p>Inside {@code freshTtl} we answer without touching LinkedIn. Between fresh and
 * {@code staleTtl} an entry is still <em>usable</em>: we try to refresh, and if the upstream
 * refuses we serve the stale copy with {@code meta.stale: true} rather than failing.
 *
 * <p>That second window is the most valuable thing in this class. Scraping a hostile
 * upstream means outages are normal, and a demo that returns a two-hour-old profile with an
 * honest age on it is worth far more than one that returns 503 while someone is watching.
 *
 * <p>Deliberately in-process, not Redis. Profile data is personal data; keeping it in memory
 * with a bounded TTL and no disk persistence is both the simplest deployment and the
 * smallest retention footprint. Redis is the documented scale-out step.
 */
@Component
public class ProfileCache {

    private final Cache<String, Entry> cache;
    private final Duration freshTtl;

    public ProfileCache(AppProperties props) {
        AppProperties.CacheSettings settings = props.cache();
        this.freshTtl = settings.freshTtl();
        this.cache = Caffeine.newBuilder()
                .maximumSize(settings.maxEntries())
                .expireAfterWrite(settings.staleTtl())
                .recordStats()
                .build();
    }

    public Optional<Entry> get(String publicIdentifier) {
        return Optional.ofNullable(cache.getIfPresent(publicIdentifier));
    }

    public void put(String publicIdentifier, Profile profile, SourceType source, double completeness) {
        cache.put(publicIdentifier, new Entry(profile, source, completeness, Instant.now()));
    }

    public void invalidate(String publicIdentifier) {
        cache.invalidate(publicIdentifier);
    }

    public boolean isFresh(Entry entry) {
        return entry.age().compareTo(freshTtl) < 0;
    }

    public long size() {
        return cache.estimatedSize();
    }

    public double hitRate() {
        return cache.stats().hitRate();
    }

    public record Entry(Profile profile, SourceType source, double completeness, Instant fetchedAt) {

        public Duration age() {
            return Duration.between(fetchedAt, Instant.now());
        }

        public long ageSeconds() {
            return Math.max(0, age().toSeconds());
        }
    }
}
