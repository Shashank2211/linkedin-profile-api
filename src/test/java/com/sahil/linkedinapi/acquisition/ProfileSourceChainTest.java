package com.sahil.linkedinapi.acquisition;

import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.support.TestProfiles;
import com.sahil.linkedinapi.support.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fallback behaviour is the part of this service most likely to matter in a live demo and
 * least likely to be exercised by hand, so it gets fake sources and explicit tests.
 */
class ProfileSourceChainTest {

    private static final Duration BUDGET = Duration.ofSeconds(10);

    /** A source whose behaviour each test dictates, and which counts its own calls. */
    private static final class FakeSource implements ProfileSource {
        private final SourceType type;
        private final boolean enabled;
        private final Supplier<Profile> behaviour;
        private final AtomicInteger calls = new AtomicInteger();

        FakeSource(SourceType type, boolean enabled, Supplier<Profile> behaviour) {
            this.type = type;
            this.enabled = enabled;
            this.behaviour = behaviour;
        }

        @Override
        public SourceType type() {
            return type;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Profile fetch(String publicIdentifier, Duration budget) {
            calls.incrementAndGet();
            return behaviour.get();
        }

        int calls() {
            return calls.get();
        }
    }

    private static FakeSource succeeding(SourceType type) {
        return new FakeSource(type, true, () -> TestProfiles.of("ada", "Ada", "Lovelace"));
    }

    private static FakeSource failing(SourceType type, String message) {
        return new FakeSource(type, true, () -> {
            throw new SourceUnavailableException(type, message);
        });
    }

    @Test
    @DisplayName("stops at the first source that answers")
    void stopsAtFirstSuccess() {
        FakeSource voyager = succeeding(SourceType.VOYAGER);
        FakeSource publicHtml = succeeding(SourceType.PUBLIC_HTML);
        var chain = new ProfileSourceChain(List.of(voyager, publicHtml), TestProperties.defaults());

        var outcome = chain.fetch("ada", BUDGET);

        assertThat(outcome.source()).isEqualTo(SourceType.VOYAGER);
        assertThat(outcome.profile().name().full()).isEqualTo("Ada Lovelace");
        assertThat(publicHtml.calls()).isZero();
    }

    @Test
    @DisplayName("falls through to the next source and reports which one served")
    void fallsThroughOnFailure() {
        FakeSource voyager = failing(SourceType.VOYAGER, "session expired");
        FakeSource publicHtml = succeeding(SourceType.PUBLIC_HTML);
        var chain = new ProfileSourceChain(List.of(voyager, publicHtml), TestProperties.defaults());

        var outcome = chain.fetch("ada", BUDGET);

        // Degraded, not down — and meta.source will tell the caller so.
        assertThat(outcome.source()).isEqualTo(SourceType.PUBLIC_HTML);
        assertThat(outcome.failedAttempts()).hasSize(1);
        assertThat(outcome.failedAttempts().get(0)).contains("VOYAGER", "session expired");
    }

    @Test
    @DisplayName("skips a disabled source without counting it as a failure")
    void skipsDisabledSource() {
        var disabled = new FakeSource(SourceType.VOYAGER, false, () -> {
            throw new AssertionError("a disabled source must never be called");
        });
        var chain = new ProfileSourceChain(
                List.of(disabled, succeeding(SourceType.PUBLIC_HTML)), TestProperties.defaults());

        assertThat(chain.fetch("ada", BUDGET).source()).isEqualTo(SourceType.PUBLIC_HTML);
    }

    @Test
    @DisplayName("a 404 is authoritative and stops the chain immediately")
    void notFoundShortCircuits() {
        var voyager = new FakeSource(SourceType.VOYAGER, true, () -> {
            throw new ProfileNotFoundException("nobody");
        });
        FakeSource publicHtml = succeeding(SourceType.PUBLIC_HTML);
        var chain = new ProfileSourceChain(List.of(voyager, publicHtml), TestProperties.defaults());

        assertThatThrownBy(() -> chain.fetch("nobody", BUDGET))
                .isInstanceOf(ProfileNotFoundException.class);

        // No other source can invent a member who does not exist.
        assertThat(publicHtml.calls()).isZero();
    }

    @Test
    @DisplayName("an auth wall everywhere is a 422, not a 503")
    void authWallEverywhereIs422() {
        var chain = new ProfileSourceChain(List.of(
                failing(SourceType.VOYAGER, "redirected to the auth wall"),
                failing(SourceType.PUBLIC_HTML, "no JSON-LD Person block — auth-walled")),
                TestProperties.defaults());

        assertThatThrownBy(() -> chain.fetch("ada", BUDGET))
                .isInstanceOf(ProfileNotAccessibleException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.PROFILE_NOT_ACCESSIBLE);
    }

    @Test
    @DisplayName("a transport outage everywhere is a 503 with a Retry-After")
    void transportOutageIs503() {
        var chain = new ProfileSourceChain(List.of(
                failing(SourceType.VOYAGER, "connection reset"),
                failing(SourceType.PUBLIC_HTML, "connect timed out")),
                TestProperties.defaults());

        assertThatThrownBy(() -> chain.fetch("ada", BUDGET))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException e = (ApiException) thrown;
                    assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
                    assertThat(e.retryAfterSeconds()).isNotNull();
                });
    }

    @Test
    @DisplayName("opens the breaker after repeated failures and stops calling that source")
    void breakerOpensAndSkipsSource() {
        // Threshold is 2 in TestProperties.
        FakeSource voyager = failing(SourceType.VOYAGER, "connection reset");
        FakeSource publicHtml = succeeding(SourceType.PUBLIC_HTML);
        var chain = new ProfileSourceChain(List.of(voyager, publicHtml), TestProperties.defaults());

        chain.fetch("a", BUDGET);
        chain.fetch("b", BUDGET);
        assertThat(voyager.calls()).isEqualTo(2);
        assertThat(chain.breakerHealth().get(SourceType.VOYAGER)).isFalse();

        var outcome = chain.fetch("c", BUDGET);

        assertThat(voyager.calls()).isEqualTo(2);   // not called a third time
        assertThat(outcome.source()).isEqualTo(SourceType.PUBLIC_HTML);
        assertThat(outcome.failedAttempts().get(0)).contains("circuit open");
    }

    @Test
    @DisplayName("a success closes the breaker again")
    void successResetsBreaker() {
        AtomicInteger attempt = new AtomicInteger();
        var flaky = new FakeSource(SourceType.VOYAGER, true, () -> {
            if (attempt.getAndIncrement() == 0) {
                throw new SourceUnavailableException(SourceType.VOYAGER, "transient");
            }
            return TestProfiles.of("ada", "Ada", "Lovelace");
        });
        var chain = new ProfileSourceChain(
                List.of(flaky, succeeding(SourceType.PUBLIC_HTML)), TestProperties.defaults());

        chain.fetch("a", BUDGET);
        chain.fetch("b", BUDGET);

        assertThat(chain.breakerHealth().get(SourceType.VOYAGER)).isTrue();
    }

    @Test
    @DisplayName("refuses to start a source with no budget left")
    void refusesWithoutBudget() {
        var chain = new ProfileSourceChain(
                List.of(succeeding(SourceType.VOYAGER)), TestProperties.defaults());

        assertThatThrownBy(() -> chain.fetch("ada", Duration.ZERO))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UPSTREAM_TIMEOUT);
    }
}
