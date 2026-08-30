package com.sahil.linkedinapi.application;

import com.sahil.linkedinapi.acquisition.ProfileSourceChain;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;
import com.sahil.linkedinapi.cache.ProfileCache;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.support.TestProfiles;
import com.sahil.linkedinapi.support.TestProperties;
import com.sahil.linkedinapi.url.InvalidProfileUrlException;
import com.sahil.linkedinapi.url.ProfileUrlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileServiceTest {

    private static final String URL = "https://www.linkedin.com/in/ada-lovelace";

    private final ProfileSourceChain chain = mock(ProfileSourceChain.class);

    private ProfileService serviceWith(AppProperties props) {
        return new ProfileService(new ProfileUrlParser(), chain, new ProfileCache(props),
                new CompletenessScorer(), props);
    }

    private ProfileSourceChain.Outcome outcome() {
        return new ProfileSourceChain.Outcome(SourceType.VOYAGER,
                TestProfiles.of("ada-lovelace", "Ada", "Lovelace"), List.of());
    }

    @Test
    @DisplayName("fetches once, then serves the cached copy inside the freshness window")
    void cachesWithinFreshWindow() {
        when(chain.fetch(anyString(), any())).thenReturn(outcome());
        var service = serviceWith(TestProperties.defaults());

        var first = service.get(URL, false);
        var second = service.get(URL, false);

        assertThat(first.source()).isEqualTo(SourceType.VOYAGER);
        assertThat(first.cached()).isFalse();

        assertThat(second.source()).isEqualTo(SourceType.CACHE);
        assertThat(second.cached()).isTrue();
        assertThat(second.stale()).isFalse();
        assertThat(second.completeness()).isEqualTo(first.completeness());

        // The whole point: LinkedIn was touched exactly once for two requests.
        verify(chain, times(1)).fetch(anyString(), any());
    }

    @Test
    @DisplayName("refresh=true bypasses the freshness window")
    void refreshBypassesCache() {
        when(chain.fetch(anyString(), any())).thenReturn(outcome());
        var service = serviceWith(TestProperties.defaults());

        service.get(URL, false);
        var forced = service.get(URL, true);

        assertThat(forced.cached()).isFalse();
        verify(chain, times(2)).fetch(anyString(), any());
    }

    @Test
    @DisplayName("serves a stale copy rather than failing when the upstream is down")
    void staleWhileRevalidate() {
        // Fresh TTL of zero makes every cached entry immediately stale but still usable.
        var props = TestProperties.withCacheTtls(Duration.ZERO, Duration.ofHours(24));
        when(chain.fetch(anyString(), any()))
                .thenReturn(outcome())
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "everything is on fire"));
        var service = serviceWith(props);

        service.get(URL, false);
        var degraded = service.get(URL, false);

        assertThat(degraded.source()).isEqualTo(SourceType.CACHE);
        assertThat(degraded.cached()).isTrue();
        assertThat(degraded.stale()).isTrue();
        assertThat(degraded.profile().name().full()).isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("propagates the upstream failure when there is nothing cached to fall back on")
    void failsWhenNothingCached() {
        when(chain.fetch(anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "everything is on fire"));
        var service = serviceWith(TestProperties.defaults());

        assertThatThrownBy(() -> service.get(URL, false))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("rejects a bad URL before spending any rate budget")
    void validatesBeforeFetching() {
        var service = serviceWith(TestProperties.defaults());

        assertThatThrownBy(() -> service.get("https://example.com/in/ada", false))
                .isInstanceOf(InvalidProfileUrlException.class);

        verify(chain, never()).fetch(anyString(), any());
    }

    @Test
    @DisplayName("eviction forces the next request back to the upstream")
    void evictionClearsTheCachedCopy() {
        when(chain.fetch(anyString(), any())).thenReturn(outcome());
        var service = serviceWith(TestProperties.defaults());

        service.get(URL, false);
        service.evict("ada-lovelace");
        service.get(URL, false);

        verify(chain, times(2)).fetch(anyString(), any());
    }

    @Test
    @DisplayName("accepts a bare identifier as well as a URL for eviction")
    void evictionAcceptsBareIdentifier() {
        var service = serviceWith(TestProperties.defaults());

        assertThat(service.toPublicIdentifier("ada-lovelace")).isEqualTo("ada-lovelace");
        assertThat(service.toPublicIdentifier(URL)).isEqualTo("ada-lovelace");
    }
}
