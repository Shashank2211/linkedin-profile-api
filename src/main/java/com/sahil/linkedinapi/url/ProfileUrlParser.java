package com.sahil.linkedinapi.url;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns whatever the caller sent into a canonical LinkedIn public identifier.
 *
 * <p>This class is also the service's SSRF boundary. <strong>The caller-supplied string
 * never reaches an outbound request.</strong> We extract a slug, validate it against a
 * strict pattern, and every downstream fetch composes its own URL from a hardcoded host
 * plus that slug. A caller cannot steer us at an internal address, a redirect chain, or
 * a different host, no matter what they put in the {@code url} parameter.
 */
@Component
public class ProfileUrlParser {

    private static final String CANONICAL_HOST = "www.linkedin.com";
    private static final String ROOT_DOMAIN = "linkedin.com";

    /**
     * Public identifiers are unicode: Cyrillic, Devanagari, Arabic and CJK slugs all exist.
     *
     * <p>{@code \p{M}} is the part that is easy to get wrong, and getting it wrong silently
     * rejects a whole class of real people. In Indic scripts a vowel sign is a separate
     * combining code point in category {@code Mn}/{@code Mc}, not a letter — the second
     * character of {@code सहिल} ("Sahil") is one. A pattern of
     * {@code [\p{L}\p{N}]} looks Unicode-aware, passes every ASCII test, and then 400s every
     * Devanagari, Tamil, Arabic and Thai identifier it ever sees. Marks are allowed anywhere
     * except the first position, where a leading combining mark would be malformed anyway.
     *
     * <p>Minimum length is two rather than three: two-character CJK identifiers are real, and
     * a slug LinkedIn would never issue simply 404s downstream — which is the honest answer
     * for it, rather than a 400 from us.
     */
    private static final Pattern SLUG =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}_-]{0,98}[\\p{L}\\p{N}\\p{M}]$");

    /** Segments that mean "this is a LinkedIn URL, but not a member profile". */
    private static final Set<String> NON_PROFILE_SEGMENTS =
            Set.of("company", "school", "showcase", "groups", "posts", "pulse", "feed",
                   "jobs", "learning", "events", "newsletters", "services");

    public String parse(String rawUrl) {
        String input = rawUrl == null ? "" : rawUrl.trim();
        if (input.isEmpty()) {
            throw new InvalidProfileUrlException("A 'url' parameter is required.");
        }

        // Tolerate "linkedin.com/in/x" and "/in/x" as well as fully-qualified URLs.
        String withScheme = input.matches("(?i)^https?://.*") ? input
                : input.startsWith("/") ? "https://" + CANONICAL_HOST + input
                : "https://" + input;

        URI uri;
        try {
            uri = URI.create(withScheme.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            throw new InvalidProfileUrlException("Not a parseable URL: " + input);
        }

        String host = uri.getHost();
        if (host == null) {
            throw new InvalidProfileUrlException("Not a parseable URL: " + input);
        }
        host = host.toLowerCase(Locale.ROOT);
        // Locale subdomains are legitimate: in.linkedin.com, de.linkedin.com, ...
        if (!host.equals(ROOT_DOMAIN) && !host.endsWith("." + ROOT_DOMAIN)) {
            throw new InvalidProfileUrlException(
                    "Only linkedin.com profile URLs are supported, got host: " + host);
        }

        // getRawPath, not getPath: getPath already percent-decodes, and decoding twice
        // mangles any identifier that legitimately contains a '%' or '+'.
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
        List<String> segments = Arrays.stream(rawPath.split("/"))
                .filter(segment -> !segment.isBlank())
                .toList();

        if (segments.isEmpty()) {
            throw new InvalidProfileUrlException(
                    "URL has no path. Expected something like https://www.linkedin.com/in/<identifier>");
        }

        String first = segments.get(0).toLowerCase(Locale.ROOT);
        if (NON_PROFILE_SEGMENTS.contains(first)) {
            throw new InvalidProfileUrlException(
                    "That is a LinkedIn /%s URL, not a member profile. Expected /in/<identifier>."
                            .formatted(first));
        }
        if (first.equals("pub")) {
            throw new InvalidProfileUrlException(
                    "Legacy /pub/ profile URLs are not supported. Open the profile in a browser "
                            + "and copy the /in/<identifier> URL it redirects to.");
        }
        if (!first.equals("in")) {
            throw new InvalidProfileUrlException(
                    "Expected a /in/<identifier> path, got /" + first);
        }
        if (segments.size() < 2) {
            throw new InvalidProfileUrlException("URL is missing the profile identifier after /in/.");
        }

        String slug = URLDecoder.decode(segments.get(1), StandardCharsets.UTF_8).trim();
        if (!SLUG.matcher(slug).matches()) {
            throw new InvalidProfileUrlException("Not a valid LinkedIn profile identifier: " + slug);
        }
        return slug;
    }

    /**
     * Accepts either a full profile URL or a bare public identifier.
     *
     * <p>The cache-eviction endpoint takes an identifier in its path, and a caller who has
     * one should not have to reassemble a URL around it just to delete it.
     */
    public String parseIdentifierOrUrl(String input) {
        String value = input == null ? "" : input.trim();
        boolean looksLikeBareSlug = !value.isEmpty()
                && value.indexOf('/') < 0
                && value.indexOf('.') < 0
                && value.indexOf(':') < 0;
        if (looksLikeBareSlug) {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8).trim();
            if (SLUG.matcher(decoded).matches()) {
                return decoded;
            }
            throw new InvalidProfileUrlException("Not a valid LinkedIn profile identifier: " + value);
        }
        return parse(value);
    }

    /** The canonical, de-tracked URL we echo back in the response. */
    public String canonicalUrl(String publicIdentifier) {
        return "https://" + CANONICAL_HOST + "/in/" + publicIdentifier;
    }
}
