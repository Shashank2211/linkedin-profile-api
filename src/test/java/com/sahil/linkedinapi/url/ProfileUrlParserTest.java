package com.sahil.linkedinapi.url;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileUrlParserTest {

    private final ProfileUrlParser parser = new ProfileUrlParser();

    @ParameterizedTest
    @DisplayName("reduces every reasonable spelling of a profile URL to the same identifier")
    @ValueSource(strings = {
            "https://www.linkedin.com/in/ada-lovelace",
            "https://www.linkedin.com/in/ada-lovelace/",
            "http://www.linkedin.com/in/ada-lovelace",
            "https://linkedin.com/in/ada-lovelace",
            "https://in.linkedin.com/in/ada-lovelace",
            "https://de.linkedin.com/in/ada-lovelace",
            "www.linkedin.com/in/ada-lovelace",
            "linkedin.com/in/ada-lovelace",
            "/in/ada-lovelace",
            "  https://www.linkedin.com/in/ada-lovelace  ",
            "https://www.linkedin.com/in/ada-lovelace?originalSubdomain=uk&trk=public_profile",
            "https://www.linkedin.com/in/ada-lovelace/en",
            "https://www.linkedin.com/in/ada-lovelace/#experience"
    })
    void normalizesUrlVariants(String input) {
        assertThat(parser.parse(input)).isEqualTo("ada-lovelace");
    }

    /*
     * The expected values below are UTF-8 literals. The project builds with
     * project.build.sourceEncoding=UTF-8 (inherited from spring-boot-starter-parent), so they
     * survive the round trip; a console that prints them as "????" is a terminal code-page
     * limitation, not a test failure.
     */

    @Test
    @DisplayName("decodes a Devanagari identifier, combining vowel signs included")
    void decodesDevanagariSlug() {
        // The third code point of this identifier (U+093F) is a combining vowel sign in
        // category Mc, NOT a letter. A slug pattern of [\p{L}\p{N}] rejects this and every
        // other Indic identifier while passing every ASCII case above — which is exactly how
        // that bug survives all the way to production.
        assertThat(parser.parse("https://www.linkedin.com/in/%E0%A4%B8%E0%A4%B9%E0%A4%BF%E0%A4%B2"))
                .isEqualTo("सहिल");
    }

    @Test
    @DisplayName("accepts identifiers in other scripts, including short CJK ones")
    void acceptsOtherScripts() {
        // Cyrillic "иван-петров"
        assertThat(parser.parse("https://ru.linkedin.com/in/%D0%B8%D0%B2%D0%B0%D0%BD-%D0%BF%D0%B5%D1%82%D1%80%D0%BE%D0%B2"))
                .isEqualTo("иван-петров");

        // A two-character CJK identifier: shorter than any Latin slug LinkedIn issues,
        // and still a real person.
        assertThat(parser.parse("https://cn.linkedin.com/in/%E5%BC%A0%E4%BC%9F"))
                .isEqualTo("张伟");
    }

    @Test
    @DisplayName("rejects an identifier that starts with a combining mark")
    void rejectsLeadingCombiningMark() {
        assertThatThrownBy(() ->
                parser.parse("https://www.linkedin.com/in/%E0%A4%BF%E0%A4%B8%E0%A4%B2"))
                .isInstanceOf(InvalidProfileUrlException.class);
    }

    @Test
    @DisplayName("names the specific problem when the URL is a company or school page")
    void rejectsNonProfileLinkedInUrls() {
        assertThatThrownBy(() -> parser.parse("https://www.linkedin.com/company/anthropic"))
                .isInstanceOf(InvalidProfileUrlException.class)
                .hasMessageContaining("/company");

        assertThatThrownBy(() -> parser.parse("https://www.linkedin.com/school/mit"))
                .isInstanceOf(InvalidProfileUrlException.class);

        assertThatThrownBy(() -> parser.parse("https://www.linkedin.com/feed/"))
                .isInstanceOf(InvalidProfileUrlException.class);
    }

    @Test
    @DisplayName("explains what to do about a legacy /pub/ URL")
    void rejectsLegacyPubUrls() {
        assertThatThrownBy(() -> parser.parse("https://www.linkedin.com/pub/ada-lovelace/1/2/3"))
                .isInstanceOf(InvalidProfileUrlException.class)
                .hasMessageContaining("/in/");
    }

    @ParameterizedTest
    @DisplayName("refuses any host that is not linkedin.com — this is the SSRF boundary")
    @ValueSource(strings = {
            "https://example.com/in/ada-lovelace",
            "https://linkedin.com.evil.test/in/ada-lovelace",
            "http://169.254.169.254/latest/meta-data/",
            "http://localhost:8080/in/ada-lovelace",
            "file:///etc/passwd"
    })
    void refusesForeignHosts(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(InvalidProfileUrlException.class);
    }

    @ParameterizedTest
    @DisplayName("rejects malformed or empty input")
    @ValueSource(strings = {"", "   ", "https://www.linkedin.com/", "https://www.linkedin.com/in/",
            "https://www.linkedin.com/in/a", "https://www.linkedin.com/in/has spaces",
            "https://www.linkedin.com/in/-leading", "https://www.linkedin.com/in/trailing-"})
    void rejectsMalformedInput(String input) {
        assertThatThrownBy(() -> parser.parse(input))
                .isInstanceOf(InvalidProfileUrlException.class);
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        assertThatThrownBy(() -> parser.parse(null))
                .isInstanceOf(InvalidProfileUrlException.class);
    }

    @Test
    @DisplayName("accepts a bare identifier as well as a URL")
    void acceptsBareIdentifier() {
        assertThat(parser.parseIdentifierOrUrl("ada-lovelace")).isEqualTo("ada-lovelace");
        assertThat(parser.parseIdentifierOrUrl("https://www.linkedin.com/in/ada-lovelace"))
                .isEqualTo("ada-lovelace");

        // One character is below the floor; two is not — see the SLUG javadoc, and the
        // two-character CJK identifier in acceptsOtherScripts that has to keep working.
        assertThatThrownBy(() -> parser.parseIdentifierOrUrl("a"))
                .isInstanceOf(InvalidProfileUrlException.class);
        assertThatThrownBy(() -> parser.parseIdentifierOrUrl("../../etc/passwd"))
                .isInstanceOf(InvalidProfileUrlException.class);
    }

    @Test
    @DisplayName("echoes back a canonical, de-tracked URL")
    void buildsCanonicalUrl() {
        assertThat(parser.canonicalUrl("ada-lovelace"))
                .isEqualTo("https://www.linkedin.com/in/ada-lovelace");
    }
}
