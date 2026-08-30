package com.sahil.linkedinapi.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahil.linkedinapi.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UrnGraphTest {

    private final JsonNode envelope = Fixtures.load("voyager-profile.json");

    @Test
    @DisplayName("indexes every entity in included[] by its entityUrn")
    void indexesIncludedEntities() {
        assertThat(UrnGraph.of(envelope).size()).isEqualTo(10);
    }

    @Test
    @DisplayName("follows data.*elements to the member profile")
    void findsRootProfile() {
        Optional<JsonNode> root = UrnGraph.of(envelope).rootProfile();

        assertThat(root).isPresent();
        assertThat(root.get().path("firstName").asText()).isEqualTo("Ada");
        assertThat(root.get().path("publicIdentifier").asText()).isEqualTo("ada-lovelace-test");
    }

    @Test
    @DisplayName("strips the * marker so mappers read ordinary field names")
    void stripsPointerMarker() {
        JsonNode root = UrnGraph.of(envelope).rootProfile().orElseThrow();

        assertThat(root.has("*profileSkills")).isFalse();
        assertThat(root.has("profileSkills")).isTrue();
    }

    @Test
    @DisplayName("inlines the position group and both of its nested roles")
    void inlinesNestedPositions() {
        JsonNode root = UrnGraph.of(envelope).rootProfile().orElseThrow();

        JsonNode groups = root.path("profilePositionGroups");
        assertThat(groups.isArray()).isTrue();
        assertThat(groups.size()).isEqualTo(1);

        JsonNode positions = groups.get(0).path("profilePositionInPositionGroup");
        assertThat(positions.size()).isEqualTo(2);
        assertThat(positions.get(0).path("title").asText()).isEqualTo("Principal Mathematician");
        assertThat(positions.get(1).path("title").asText()).isEqualTo("Mathematician");
    }

    @Test
    @DisplayName("terminates on a cycle, leaving the back-reference as a URN string")
    void terminatesOnCycle() {
        // The company points back at the member via *employees. Without the visited set
        // this recurses until the stack gives out.
        JsonNode root = UrnGraph.of(envelope).rootProfile().orElseThrow();

        JsonNode employees = root.path("profilePositionGroups").get(0)
                .path("company").path("employees");

        assertThat(employees.size()).isEqualTo(1);
        assertThat(employees.get(0).isTextual()).isTrue();
        assertThat(employees.get(0).asText()).isEqualTo("urn:li:fsd_profile:ACoAAA_TEST_1");
    }

    @Test
    @DisplayName("keeps an unresolvable pointer as its URN rather than dropping it")
    void keepsDanglingReference() {
        JsonNode dangling = Fixtures.parse("""
                {
                  "data": { "*elements": ["urn:li:fsd_profile:X"] },
                  "included": [
                    { "entityUrn": "urn:li:fsd_profile:X",
                      "firstName": "Grace",
                      "*company": "urn:li:fsd_company:not-in-this-payload" }
                  ]
                }
                """);

        JsonNode root = UrnGraph.of(dangling).rootProfile().orElseThrow();

        assertThat(root.path("company").isTextual()).isTrue();
        assertThat(root.path("company").asText()).isEqualTo("urn:li:fsd_company:not-in-this-payload");
    }

    @Test
    @DisplayName("falls back to scanning included[] when the envelope shape is unfamiliar")
    void scansWhenEnvelopeShapeChanges() {
        JsonNode odd = Fixtures.parse("""
                {
                  "data": { "somethingElse": true },
                  "included": [
                    { "entityUrn": "urn:li:fsd_profilePosition:(X,1)", "title": "Engineer" },
                    { "entityUrn": "urn:li:fsd_profile:X", "firstName": "Grace", "lastName": "Hopper" }
                  ]
                }
                """);

        JsonNode root = UrnGraph.of(odd).rootProfile().orElseThrow();

        assertThat(root.path("firstName").asText()).isEqualTo("Grace");
    }

    @Test
    @DisplayName("follows the GraphQL envelope, where the collection is a level deeper")
    void findsRootProfileInGraphQlEnvelope() {
        // /voyager/api/graphql nests the collection under data.data and keys it by query
        // name, so the pointer is at data.data.<queryName>."*elements". This shape is what
        // the LinkedIn web app actually returns now — captured payloads carry
        // meta.microSchema.isGraphQL: true. The query name is deliberately not hardcoded.
        JsonNode graphQl = Fixtures.parse("""
                {
                  "data": {
                    "data": {
                      "identityDashProfilesByMemberIdentity": {
                        "*elements": ["urn:li:fsd_profile:X"],
                        "$type": "com.linkedin.restli.common.CollectionResponse"
                      }
                    },
                    "extensions": {}
                  },
                  "meta": { "microSchema": { "isGraphQL": true } },
                  "included": [
                    { "entityUrn": "urn:li:fsd_profile:X",
                      "firstName": "Grace", "lastName": "Hopper",
                      "*profileSkills": ["urn:li:fsd_profileSkill:(X,1)"] },
                    { "entityUrn": "urn:li:fsd_profileSkill:(X,1)", "name": "COBOL" }
                  ]
                }
                """);

        JsonNode root = UrnGraph.of(graphQl).rootProfile().orElseThrow();

        assertThat(root.path("firstName").asText()).isEqualTo("Grace");
        // Pointers still resolve once the root is found by the deeper route.
        assertThat(root.path("profileSkills").get(0).path("name").asText()).isEqualTo("COBOL");
    }

    @Test
    @DisplayName("returns empty rather than throwing on a payload with no profile")
    void emptyWhenNoProfile() {
        assertThat(UrnGraph.of(Fixtures.parse("{\"included\":[]}")).rootProfile()).isEmpty();
        assertThat(UrnGraph.of(null).rootProfile()).isEmpty();
    }
}
