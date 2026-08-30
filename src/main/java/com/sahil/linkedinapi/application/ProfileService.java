package com.sahil.linkedinapi.application;

import com.sahil.linkedinapi.acquisition.ProfileSourceChain;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.cache.ProfileCache;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.url.ProfileUrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Orchestrates one request: validate, check cache, fetch, score, cache, answer.
 *
 * <p>Everything fragile lives behind {@code ProfileSourceChain}; everything expensive lives
 * behind {@code ProfileCache}. What is left here is the policy — when a cached copy is good
 * enough, and what to do when LinkedIn says no.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final ProfileUrlParser urls;
    private final ProfileSourceChain chain;
    private final ProfileCache cache;
    private final CompletenessScorer scorer;
    private final AppProperties props;

    public ProfileService(ProfileUrlParser urls, ProfileSourceChain chain, ProfileCache cache,
                          CompletenessScorer scorer, AppProperties props) {
        this.urls = urls;
        this.chain = chain;
        this.cache = cache;
        this.scorer = scorer;
        this.props = props;
    }

    public Result get(String rawUrl, boolean forceRefresh) {
        long startedAt = System.nanoTime();
        String publicIdentifier = urls.parse(rawUrl);

        Optional<ProfileCache.Entry> cached = cache.get(publicIdentifier);

        if (!forceRefresh && cached.isPresent() && cache.isFresh(cached.get())) {
            return fromCache(cached.get(), publicIdentifier, false, startedAt);
        }

        try {
            ProfileSourceChain.Outcome outcome = chain.fetch(publicIdentifier, props.requestBudget());
            double completeness = scorer.score(outcome.profile());
            cache.put(publicIdentifier, outcome.profile(), outcome.source(), completeness);
            return new Result(outcome.profile(), outcome.source(), Instant.now(),
                    false, 0L, false, completeness, elapsedMs(startedAt));
        } catch (ApiException e) {
            // Stale-while-revalidate: a slightly old profile beats a 503, and meta says which.
            if (cached.isPresent()) {
                log.warn("Serving a stale copy of {} ({}s old) — upstream failed: {}",
                        publicIdentifier, cached.get().ageSeconds(), e.getMessage());
                return fromCache(cached.get(), publicIdentifier, true, startedAt);
            }
            throw e;
        }
    }

    /** Removes one profile from the cache. Backs the data-deletion endpoint. */
    public void evict(String publicIdentifier) {
        cache.invalidate(publicIdentifier);
        log.info("Evicted {} from cache on request", publicIdentifier);
    }

    public String toPublicIdentifier(String rawUrlOrSlug) {
        return urls.parseIdentifierOrUrl(rawUrlOrSlug);
    }

    private Result fromCache(ProfileCache.Entry entry, String publicIdentifier,
                             boolean stale, long startedAt) {
        log.debug("Cache hit for {} ({}s old, stale={})", publicIdentifier, entry.ageSeconds(), stale);
        return new Result(entry.profile(), SourceType.CACHE, entry.fetchedAt(),
                true, entry.ageSeconds(), stale, entry.completeness(), elapsedMs(startedAt));
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    public record Result(
            Profile profile,
            SourceType source,
            Instant fetchedAt,
            boolean cached,
            long cacheAgeSeconds,
            boolean stale,
            double completeness,
            long durationMs
    ) {
    }
}
