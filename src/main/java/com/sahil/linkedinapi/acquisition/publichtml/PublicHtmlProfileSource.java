package com.sahil.linkedinapi.acquisition.publichtml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahil.linkedinapi.acquisition.PaceGate;
import com.sahil.linkedinapi.acquisition.ProfileNotFoundException;
import com.sahil.linkedinapi.acquisition.ProfileSource;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.acquisition.SourceUnavailableException;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.url.ProfileUrlParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Fallback source: the logged-out public profile page.
 *
 * <p>Far thinner than Voyager — LinkedIn shows anonymous visitors a fraction of a profile
 * and increasingly hides even that behind an auth wall. It earns its place for two
 * reasons. It needs no session, so it still answers when every cookie is quarantined; and
 * it is the only path here that reads data LinkedIn publishes publicly, which is the
 * safest ground to stand on.
 *
 * <p>We read the embedded JSON-LD block first and only fall back to the DOM, because the
 * JSON-LD is a stable, documented schema.org structure while the markup around it is
 * generated class names that change weekly.
 */
@Component
@Order(2)
public class PublicHtmlProfileSource implements ProfileSource {

    private static final Logger log = LoggerFactory.getLogger(PublicHtmlProfileSource.class);

    /** Two hops covers canonical and locale redirects; more than that is a loop. */
    private static final int MAX_REDIRECTS = 2;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final JsonLdProfileMapper jsonLd;
    private final ProfileUrlParser urls;
    private final AppProperties props;
    private final AppProperties.SourceSettings settings;
    private final PaceGate pace;

    public PublicHtmlProfileSource(HttpClient linkedInHttpClient, ObjectMapper mapper,
                                   JsonLdProfileMapper jsonLd, ProfileUrlParser urls,
                                   AppProperties props) {
        this.http = linkedInHttpClient;
        this.mapper = mapper;
        this.jsonLd = jsonLd;
        this.urls = urls;
        this.props = props;
        this.settings = props.sources().publicHtml();
        this.pace = new PaceGate(settings.minInterval(), settings.jitter());
    }

    @Override
    public SourceType type() {
        return SourceType.PUBLIC_HTML;
    }

    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    @Override
    public Profile fetch(String publicIdentifier, Duration budget) {
        Duration remaining = pace.await(type(), budget);
        Duration timeout = settings.timeout().compareTo(remaining) <= 0 ? settings.timeout() : remaining;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new SourceUnavailableException(type(), "No budget left for a public-page fetch.");
        }

        URI uri = URI.create(settings.baseUrl() + "/in/" + publicIdentifier + "/");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("user-agent", props.session().userAgent())
                .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("accept-language", props.session().acceptLanguage())
                .GET()
                .build();

        HttpResponse<String> response = sendFollowingSafeRedirects(request, timeout);

        int status = response.statusCode();
        if (status == 404 || status == 410) {
            throw new ProfileNotFoundException(publicIdentifier);
        }
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("location").orElse("");
            String lower = location.toLowerCase(Locale.ROOT);
            if (isAuthWall(lower)) {
                throw new SourceUnavailableException(type(),
                        "The public page redirected to the LinkedIn auth wall.");
            }
            // Naming the target matters: "unexpected redirect" alone gives whoever is
            // debugging this nothing to act on, and the target is the whole diagnosis.
            throw new SourceUnavailableException(type(),
                    "Unexpected redirect from the public page (status " + status + ") to "
                            + (location.isBlank() ? "no Location header" : location) + ".");
        }
        if (status == 999 || status == 429) {
            throw new SourceUnavailableException(type(),
                    "LinkedIn is throttling anonymous requests (HTTP " + status + ").");
        }
        if (status != 200 || response.body() == null || response.body().isBlank()) {
            throw new SourceUnavailableException(type(), "Public page returned HTTP " + status + ".");
        }

        Document document = Jsoup.parse(response.body(), settings.baseUrl());
        JsonNode person = findPersonNode(document);
        if (person == null) {
            throw new SourceUnavailableException(type(),
                    "No JSON-LD Person block on the public page — it is most likely auth-walled.");
        }
        log.debug("Public page yielded a JSON-LD Person block for {}", publicIdentifier);
        return jsonLd.map(person, publicIdentifier, urls.canonicalUrl(publicIdentifier));
    }

    /** Paths that mean "log in", whatever status code carries them. */
    private static final List<String> AUTH_WALL_MARKERS =
            List.of("authwall", "/login", "uas/login", "checkpoint", "challenge", "signup");

    private static boolean isAuthWall(String lowercaseLocation) {
        return AUTH_WALL_MARKERS.stream().anyMatch(lowercaseLocation::contains);
    }

    /**
     * Follows a redirect only when it is demonstrably benign.
     *
     * <p>The client is built with {@code Redirect.NEVER} on purpose: a 302 to {@code
     * /authwall} is the clearest signal LinkedIn gives that we are not welcome, and following
     * it turns that signal into a 200 containing a login page, which the mapper then reports
     * as an empty profile. That reasoning is sound for auth walls and wrong for everything
     * else — LinkedIn also emits ordinary canonical and locale redirects, and refusing those
     * throws away a page we are allowed to read.
     *
     * <p>So: follow, but only when all three hold.
     * <ul>
     *   <li>The target is not an auth wall, login, checkpoint or signup path.</li>
     *   <li>The target host is {@code linkedin.com} or a subdomain — the same allowlist
     *       {@link ProfileUrlParser} enforces on input, applied again here so a redirect
     *       cannot walk us off the permitted host. This is the SSRF boundary, and a redirect
     *       chain is exactly how it would otherwise be bypassed.</li>
     *   <li>We have taken fewer than {@value #MAX_REDIRECTS} hops.</li>
     * </ul>
     *
     * <p>Anything else is returned to the caller as the redirect it is, and classified above.
     */
    private HttpResponse<String> sendFollowingSafeRedirects(HttpRequest request, Duration timeout) {
        HttpRequest current = request;
        for (int hop = 0; ; hop++) {
            HttpResponse<String> response;
            try {
                response = http.send(current, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new SourceUnavailableException(type(), "Public page fetch failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceUnavailableException(type(), "Public page fetch interrupted.", e);
            }

            int status = response.statusCode();
            if (status < 300 || status >= 400 || hop >= MAX_REDIRECTS) {
                return response;
            }

            String location = response.headers().firstValue("location").orElse("");
            if (location.isBlank() || isAuthWall(location.toLowerCase(Locale.ROOT))) {
                return response;
            }

            URI target;
            try {
                // Resolve against the current URI so a relative Location works.
                target = current.uri().resolve(location);
            } catch (IllegalArgumentException e) {
                return response;
            }
            if (!isLinkedInHost(target)) {
                log.warn("Public page redirected off-host to {} — refusing to follow", target.getHost());
                return response;
            }

            log.debug("Public page redirected ({}) to {}, following hop {}", status, target, hop + 1);
            current = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .header("user-agent", props.session().userAgent())
                    .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("accept-language", props.session().acceptLanguage())
                    .GET()
                    .build();
        }
    }

    private static boolean isLinkedInHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("linkedin.com") || host.endsWith(".linkedin.com");
    }

    /** Finds the schema.org Person inside any ld+json block on the page. */
    private JsonNode findPersonNode(Document document) {
        for (Element script : document.select("script[type=application/ld+json]")) {
            try {
                JsonNode root = mapper.readTree(script.data());
                JsonNode found = searchForPerson(root);
                if (found != null) {
                    return found;
                }
            } catch (Exception e) {
                log.debug("Skipping an unparseable ld+json block: {}", e.getMessage());
            }
        }
        return null;
    }

    private JsonNode searchForPerson(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            if ("Person".equals(node.path("@type").asText())) {
                return node;
            }
            for (String container : new String[]{"@graph", "mainEntity", "mainEntityOfPage"}) {
                JsonNode found = searchForPerson(node.path(container));
                if (found != null) {
                    return found;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = searchForPerson(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
