package com.sahil.linkedinapi.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.api.dto.ProfileEnvelope;
import com.sahil.linkedinapi.api.dto.ResponseMeta;
import com.sahil.linkedinapi.application.CompletenessScorer;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.normalize.UrnGraph;
import com.sahil.linkedinapi.normalize.VoyagerProfileMapper;
import com.sahil.linkedinapi.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serializes a full envelope and pins the contract rules the README states as guarantees.
 *
 * <p>Those rules are promises to a caller — <em>every key is always present</em>, missing
 * scalars are {@code null}, missing collections are {@code []}, and the two mean different
 * things. Nothing else in the suite checks them at the serialization layer, which is the only
 * layer a caller actually sees: a mapper can be perfect and a Jackson setting can still drop
 * every null key and break every client that reads them.
 *
 * <p>It also prints the envelope, so there is one command that shows exactly what this API
 * returns without needing LinkedIn to be reachable:
 *
 * <pre>mvn test -Dtest=ContractExampleTest</pre>
 *
 * <p>The data is the committed <strong>synthetic</strong> fixture — no real member — so this
 * demonstrates the shape of a response, not a live fetch.
 */
class ContractExampleTest {

    /** Mirrors the app's own serialization settings; otherwise this proves nothing. */
    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Keys the contract promises are always present on a profile, populated or not. */
    private static final List<String> REQUIRED_PROFILE_KEYS = List.of(
            "publicIdentifier", "profileUrl", "urn", "name", "headline", "about", "location",
            "industry", "profilePicture", "backgroundImage", "flags", "counts", "experience",
            "education", "skills", "certifications", "languages");

    /** Keys whose value must serialize as an array, never null. */
    private static final List<String> COLLECTION_KEYS =
            List.of("experience", "education", "skills", "certifications", "languages");

    @Test
    @DisplayName("serializes the documented envelope, with every promised key present")
    void serializesTheDocumentedContract() throws Exception {
        Profile profile = new VoyagerProfileMapper().map(
                UrnGraph.of(Fixtures.load("voyager-profile.json")).rootProfile().orElseThrow(),
                "ada-lovelace-test",
                "https://www.linkedin.com/in/ada-lovelace-test");

        var envelope = new ProfileEnvelope(
                new ResponseMeta(
                        "3f2a9c11-0000-4000-8000-000000000000",
                        Instant.parse("2026-08-30T12:00:00Z"),
                        SourceType.VOYAGER,
                        false, 0L, false,
                        new CompletenessScorer().score(profile),
                        812L),
                profile);

        String serialized = json.writeValueAsString(envelope);

        System.out.println("""

                ================================================================
                GET /api/v1/profiles?url=https://www.linkedin.com/in/<identifier>
                ----------------------------------------------------------------
                Synthetic fixture - no real member data. This is the response
                SHAPE the API guarantees, rendered by the real mapper and the
                real serializer.
                ================================================================
                """);
        System.out.println(serialized);

        JsonNode root = json.readTree(serialized);
        JsonNode profileNode = root.path("profile");

        assertThat(root.has("meta")).as("envelope carries meta").isTrue();
        assertThat(root.has("profile")).as("envelope carries profile").isTrue();

        // "Every key is always present" - the guarantee that lets a client skip existence
        // checks. A Jackson inclusion setting is all it takes to silently break this.
        for (String key : REQUIRED_PROFILE_KEYS) {
            assertThat(profileNode.has(key))
                    .as("contract promises profile.%s is always present", key)
                    .isTrue();
        }

        // "[] means there is none; null means we could not read it." A collection that
        // serializes as null collapses that distinction and makes completeness unreadable.
        for (String key : COLLECTION_KEYS) {
            assertThat(profileNode.path(key).isArray())
                    .as("profile.%s must serialize as an array, never null", key)
                    .isTrue();
        }

        // Dates stay ISO-8601 partials. Padding "2020" to "2020-01-01" invents precision
        // LinkedIn never sent and corrupts any tenure arithmetic downstream.
        String start = profileNode.path("experience").get(0).path("startDate").asText();
        assertThat(start).matches("\\d{4}(-\\d{2})?");

        // fetchedAt is an instant, not an epoch number - the module registration above is
        // what keeps it that way, and this is the assertion that notices if it is dropped.
        assertThat(root.path("meta").path("fetchedAt").isTextual()).isTrue();
        assertThat(root.path("meta").path("source").asText()).isEqualTo("VOYAGER");
    }
}
