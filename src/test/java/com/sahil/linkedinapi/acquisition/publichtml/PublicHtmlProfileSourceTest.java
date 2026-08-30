package com.sahil.linkedinapi.acquisition.publichtml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahil.linkedinapi.acquisition.SourceUnavailableException;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.support.TestProperties;
import com.sahil.linkedinapi.url.ProfileUrlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins redirect handling on the public-page source.
 *
 * <p>This is security-relevant, not cosmetic. The service's central claim is that a caller
 * cannot steer it at another host: the URL parser reduces input to a slug, and every fetch
 * composes its own URL. A followed redirect is the one path that can walk around that,
 * because the target comes from the response rather than from us — so the host allowlist has
 * to be enforced again here, and that has to be tested.
 *
 * <p>The other half is the auth wall. Following a redirect to a login page turns LinkedIn's
 * clearest "you are not welcome" signal into a 200 with a login page in the body, which the
 * mapper then reports as an empty profile. A redirect is followed only when it is neither.
 *
 * <p>The fakes are built <em>before</em> each {@code when(...)} call rather than inline:
 * constructing a mock inside another mock's {@code thenReturn(...)} argument is nested
 * stubbing, and Mockito rejects it as an unfinished stub.
 */
class PublicHtmlProfileSourceTest {

    private final HttpClient http = mock(HttpClient.class);

    private final PublicHtmlProfileSource source = new PublicHtmlProfileSource(
            http, new ObjectMapper(), new JsonLdProfileMapper(),
            new ProfileUrlParser(), TestProperties.defaults());

    private static final String PAGE_WITH_PERSON = """
            <html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"Person","name":"Grace Hopper",
               "jobTitle":"Rear Admiral",
               "address":{"@type":"PostalAddress","addressLocality":"Arlington, Virginia"}}
            ]}
            </script>
            </head><body></body></html>
            """;

    @Test
    @DisplayName("follows a same-host redirect and reads the page it lands on")
    void followsSameHostRedirect() throws Exception {
        HttpResponse<String> hop = redirect(301, "https://www.linkedin.com/in/grace-hopper/");
        HttpResponse<String> page = ok(PAGE_WITH_PERSON);
        when(http.<String>send(any(), any())).thenReturn(hop, page);

        Profile profile = source.fetch("grace-hopper", Duration.ofSeconds(10));

        assertThat(profile.name().full()).isEqualTo("Grace Hopper");
        verify(http, times(2)).send(any(), any());
    }

    @Test
    @DisplayName("follows a locale subdomain redirect - in.linkedin.com is still LinkedIn")
    void followsLocaleSubdomainRedirect() throws Exception {
        HttpResponse<String> hop = redirect(302, "https://in.linkedin.com/in/grace-hopper/");
        HttpResponse<String> page = ok(PAGE_WITH_PERSON);
        when(http.<String>send(any(), any())).thenReturn(hop, page);

        assertThat(source.fetch("grace-hopper", Duration.ofSeconds(10)).name().full())
                .isEqualTo("Grace Hopper");
    }

    @Test
    @DisplayName("refuses to follow a redirect off linkedin.com - the SSRF boundary")
    void refusesOffHostRedirect() throws Exception {
        HttpResponse<String> offHost = redirect(302, "https://evil.test/in/grace-hopper/");
        when(http.<String>send(any(), any())).thenReturn(offHost);

        assertThatThrownBy(() -> source.fetch("grace-hopper", Duration.ofSeconds(10)))
                .isInstanceOf(SourceUnavailableException.class);

        // The point is that the second request never happens: one send, no follow.
        verify(http, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("does not follow an auth-wall redirect, and says so")
    void refusesAuthWallRedirect() throws Exception {
        HttpResponse<String> wall = redirect(302, "https://www.linkedin.com/authwall?trk=bf");
        when(http.<String>send(any(), any())).thenReturn(wall);

        assertThatThrownBy(() -> source.fetch("grace-hopper", Duration.ofSeconds(10)))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("auth wall");

        verify(http, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("names the redirect target when it will not follow it")
    void reportsTheRedirectTarget() throws Exception {
        // Three identical hops: two get followed, the third is returned and classified.
        HttpResponse<String> feed = redirect(303, "https://www.linkedin.com/feed/");
        when(http.<String>send(any(), any())).thenReturn(feed, feed, feed);

        // "Unexpected redirect" with no target gives whoever is debugging this nothing.
        assertThatThrownBy(() -> source.fetch("grace-hopper", Duration.ofSeconds(10)))
                .isInstanceOf(SourceUnavailableException.class)
                .hasMessageContaining("/feed/");
    }

    @Test
    @DisplayName("stops after the redirect cap rather than looping")
    void stopsAfterRedirectCap() throws Exception {
        HttpResponse<String> a = redirect(302, "https://www.linkedin.com/in/a/");
        HttpResponse<String> b = redirect(302, "https://www.linkedin.com/in/b/");
        HttpResponse<String> c = redirect(302, "https://www.linkedin.com/in/c/");
        HttpResponse<String> page = ok(PAGE_WITH_PERSON);
        when(http.<String>send(any(), any())).thenReturn(a, b, c, page);

        assertThatThrownBy(() -> source.fetch("grace-hopper", Duration.ofSeconds(10)))
                .isInstanceOf(SourceUnavailableException.class);

        // Initial request plus two follows, then it gives up rather than chasing forever.
        verify(http, times(3)).send(any(), any());
    }

    @Test
    @DisplayName("carries the browser-ish headers onto the followed request")
    void followedRequestKeepsHeaders() throws Exception {
        HttpResponse<String> hop = redirect(301, "https://www.linkedin.com/in/grace-hopper/");
        HttpResponse<String> page = ok(PAGE_WITH_PERSON);
        when(http.<String>send(any(), any())).thenReturn(hop, page);

        source.fetch("grace-hopper", Duration.ofSeconds(10));

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captor.capture(), any());
        HttpRequest followed = captor.getAllValues().get(1);
        // A follow that drops the user-agent looks like a bot to the very system we are
        // trying to read, which would make the redirect pointless to have followed.
        assertThat(followed.headers().firstValue("user-agent")).isPresent();
        assertThat(followed.headers().firstValue("accept-language")).isPresent();
        assertThat(followed.uri().toString()).endsWith("/in/grace-hopper/");
    }

    // --- fakes ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private HttpResponse<String> redirect(int status, String location) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(
                HttpHeaders.of(Map.of("location", List.of(location)), (a, b) -> true));
        when(response.uri()).thenReturn(URI.create("https://www.linkedin.com/in/grace-hopper/"));
        return response;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> ok(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.uri()).thenReturn(URI.create("https://www.linkedin.com/in/grace-hopper/"));
        return response;
    }
}
