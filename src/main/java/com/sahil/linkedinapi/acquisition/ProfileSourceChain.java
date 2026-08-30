package com.sahil.linkedinapi.acquisition;

import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.domain.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tries each enabled source in order until one answers, spending down a single shared
 * wall-clock budget.
 *
 * <p>One budget for the whole chain, not a timeout per source. Independent per-source
 * timeouts look reasonable in isolation and then stack: three sources at ten seconds each
 * is a caller waiting thirty seconds for a failure. Here the budget is set once at the
 * edge and every source gets whatever is left.
 *
 * <p>Sources are injected in {@code @Order} sequence, so adding a third one is a new class
 * with an annotation and no change to this file.
 */
@Component
public class ProfileSourceChain {

    private static final Logger log = LoggerFactory.getLogger(ProfileSourceChain.class);

    private final List<ProfileSource> sources;
    private final Map<SourceType, SourceBreaker> breakers = new EnumMap<>(SourceType.class);

    public ProfileSourceChain(List<ProfileSource> sources, AppProperties props) {
        this.sources = sources;
        for (ProfileSource source : sources) {
            breakers.put(source.type(), new SourceBreaker(
                    props.breaker().failureThreshold(), props.breaker().openDuration()));
        }
        log.info("Source chain: {}", sources.stream().map(s -> s.type().name()).toList());
    }

    public Outcome fetch(String publicIdentifier, Duration budget) {
        Instant deadline = Instant.now().plus(budget);
        List<String> attempts = new ArrayList<>();

        for (ProfileSource source : sources) {
            SourceType type = source.type();

            if (!source.enabled()) {
                attempts.add(type + ": disabled or unconfigured");
                continue;
            }
            SourceBreaker breaker = breakers.get(type);
            if (!breaker.allowsRequest()) {
                attempts.add(type + ": circuit open until " + breaker.openUntil());
                continue;
            }

            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                throw new ApiException(ErrorCode.UPSTREAM_TIMEOUT,
                        "Request budget exhausted before any source answered. Attempts: "
                                + String.join(" | ", attempts));
            }

            try {
                Profile profile = source.fetch(publicIdentifier, remaining);
                breaker.recordSuccess();
                return new Outcome(type, profile, List.copyOf(attempts));
            } catch (ProfileNotFoundException e) {
                // Authoritative. No other source can invent a member who does not exist.
                breaker.recordSuccess();
                throw e;
            } catch (SourceUnavailableException e) {
                breaker.recordFailure();
                attempts.add(type + ": " + e.getMessage());
                log.info("Source {} unavailable for {} — {}", type, publicIdentifier, e.getMessage());
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                breaker.recordFailure();
                attempts.add(type + ": unexpected " + e.getClass().getSimpleName());
                log.warn("Source {} threw unexpectedly for {}", type, publicIdentifier, e);
            }
        }

        throw exhausted(publicIdentifier, attempts);
    }

    /**
     * Chooses the honest status for "every source failed".
     *
     * <p>If the failures look like walls rather than outages, that is a 422: the request was
     * fine, the data is simply not visible from here, and telling the caller to retry would
     * waste their time. Anything else is a 503 with a Retry-After.
     */
    private ApiException exhausted(String publicIdentifier, List<String> attempts) {
        String joined = String.join(" | ", attempts);
        String lower = joined.toLowerCase();
        boolean walled = lower.contains("auth wall") || lower.contains("authwall")
                || lower.contains("no member profile entity") || lower.contains("private");

        if (walled) {
            return new ProfileNotAccessibleException(
                    "Could not read '" + publicIdentifier + "'. The profile is private, or every "
                            + "available source hit an authentication wall. Attempts: " + joined);
        }
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE,
                "No source could serve '" + publicIdentifier + "'. Attempts: " + joined,
                60L, null);
    }

    /** Snapshot of breaker state, for the health endpoint. */
    public Map<SourceType, Boolean> breakerHealth() {
        Map<SourceType, Boolean> out = new EnumMap<>(SourceType.class);
        breakers.forEach((type, breaker) -> out.put(type, !breaker.isOpen()));
        return out;
    }

    public record Outcome(SourceType source, Profile profile, List<String> failedAttempts) {
    }
}
