package com.sahil.linkedinapi.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahil.linkedinapi.application.CompletenessScorer;
import com.sahil.linkedinapi.domain.Profile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reports which mapper fields survive contact with a <em>real</em> captured payload.
 *
 * <p>The committed fixture is synthetic, so every field path in {@link VoyagerProfileMapper}
 * is an informed guess until a genuine response settles it. Checking that by hand means
 * reading a multi-megabyte JSON against thirty-odd multi-path lookups. This does it
 * mechanically: it maps the real fixture, prints every field that came back null, and — the
 * part that actually saves time — for each failed path says <em>how far it got and what keys
 * were available where it stopped</em>. That usually names the correct path outright.
 *
 * <p>Run it on its own to read the report:
 * <pre>mvn test -Dtest=RealFixtureMappingReportTest</pre>
 *
 * <p><strong>This test skips when no real fixture is present</strong>, so a clean clone with
 * no credentials still goes green — which is the promise the README makes to a reviewer.
 * Drop any file named {@code voyager-real*.json} into {@code src/test/resources/fixtures/}
 * and it starts reporting.
 *
 * <p>The assertions here are deliberately minimal. A 2nd-degree profile legitimately returns
 * far less than your own, so field-level assertions would encode one capture's shape as a
 * requirement. What is pinned is only what must hold for <em>any</em> real payload: the graph
 * resolves, and a member has a name. Everything else is a report for a human to act on.
 */
@DisplayName("Real-fixture mapping report")
class RealFixtureMappingReportTest {

    private static final Path FIXTURE_DIR = Path.of("src", "test", "resources", "fixtures");
    private static final String REAL_FIXTURE_PREFIX = "voyager-real";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A field the mapper reads, and the candidate paths it tries in order.
     *
     * <p>This table mirrors {@link VoyagerProfileMapper}. It is duplicated rather than
     * derived because the mapper's paths are inline arguments, and refactoring production
     * code to make a diagnostic prettier is the wrong trade. If a path is added there and
     * not here the report simply reports one path fewer — it cannot break the build.
     */
    private record Field(String label, boolean collection, List<String> paths) {
        static Field of(String label, String... paths) {
            return new Field(label, false, List.of(paths));
        }

        static Field collection(String label, String... paths) {
            return new Field(label, true, List.of(paths));
        }
    }

    private static final List<Field> FIELDS = List.of(
            Field.of("publicIdentifier", "publicIdentifier"),
            Field.of("urn", "entityUrn"),
            Field.of("name.first", "firstName"),
            Field.of("name.last", "lastName"),
            Field.of("headline", "headline"),
            Field.of("about", "summary", "about"),
            Field.of("location.raw",
                    "geoLocation.geo.defaultLocalizedName", "geoLocationName", "locationName"),
            Field.of("location.city", "geoLocation.geo.defaultLocalizedNameWithoutCountryName"),
            Field.of("location.country", "geoLocation.geo.country.countryCode",
                    "location.basicLocation.countryCode", "geoCountry.countryCode"),
            Field.of("industry", "industry.name", "industryV2.name", "industryName"),
            Field.of("profilePicture", "profilePicture", "picture"),
            Field.of("backgroundImage", "backgroundPicture", "backgroundImage"),
            Field.of("flags.openToWork", "memberBadges.openToWork", "openToWork"),
            Field.of("flags.premium", "memberBadges.premium", "premium"),
            Field.of("flags.influencer", "memberBadges.influencer", "influencer"),
            Field.of("counts.connections", "connections.paging.total", "connectionsCount"),
            Field.of("counts.followers", "followingState.followerCount", "followersCount"),
            Field.collection("experience (groups)",
                    "profilePositionGroups", "positionGroups", "positionView"),
            Field.collection("experience (flat fallback)", "profilePositions", "positions"),
            Field.collection("education", "profileEducations", "educations", "educationView"),
            Field.collection("skills", "profileSkills", "skills", "skillView"),
            Field.collection("certifications", "profileCertifications", "certifications"),
            Field.collection("languages", "profileLanguages", "languages"));

    @Test
    @DisplayName("maps every real fixture and reports the field paths that came back empty")
    void reportsUnmappedFieldsAgainstRealFixtures() throws IOException {
        List<Path> fixtures = realFixtures();

        Assumptions.assumeFalse(fixtures.isEmpty(), """
                No real fixture found — skipping.

                This is the expected state on a clean clone: the suite runs without \
                credentials and without network access.

                To enable this report, capture a payload per docs/capturing-fixtures.md, \
                redact it, and write it to src/test/resources/fixtures/ under a name \
                starting with "voyager-real". For example:

                  java tools/FixtureRedactor.java \\
                       src/test/resources/fixtures/raw/me.json \\
                       src/test/resources/fixtures/voyager-real-full.json
                """);

        for (Path fixture : fixtures) {
            report(fixture);
        }
    }

    private void report(Path fixture) throws IOException {
        JsonNode envelope = JSON.readTree(Files.readString(fixture));
        UrnGraph graph = UrnGraph.of(envelope);
        Optional<JsonNode> root = graph.rootProfile();

        StringBuilder out = new StringBuilder();
        rule(out, "FIXTURE  " + fixture.getFileName());
        out.append("  entities indexed from included[] : ").append(graph.size()).append('\n');
        out.append("  root profile resolved            : ").append(root.isPresent()).append('\n');

        // A real capture that indexes nothing is a capture of the wrong request — usually the
        // page HTML, or a Voyager call other than identity/dash/profiles. Say so plainly,
        // because every field below would otherwise report as null and look like a mapper bug.
        if (root.isEmpty()) {
            out.append("""

                      No member profile in this payload.

                      Most likely this is not the identity/dash/profiles response. In DevTools,
                      filter the Network tab on "voyager" and look for the request whose URL
                      contains identity/dash/profiles?q=memberIdentity — then Copy > Copy response.
                    """);
            System.out.println(out);
            throw new AssertionError(fixture.getFileName()
                    + " contains no resolvable member profile — see the report above.");
        }

        JsonNode profileNode = root.get();
        Profile profile = new VoyagerProfileMapper()
                .map(profileNode, "member-one", "https://www.linkedin.com/in/member-one");
        double completeness = new CompletenessScorer().score(profile);

        out.append("  completeness score               : ").append(completeness).append('\n');

        List<Field> mapped = new ArrayList<>();
        List<Field> missing = new ArrayList<>();
        for (Field field : FIELDS) {
            (isPopulated(profileNode, field) ? mapped : missing).add(field);
        }

        section(out, "MAPPED (" + mapped.size() + ")");
        for (Field field : mapped) {
            out.append("  + ").append(field.label()).append('\n');
        }

        section(out, "EMPTY (" + missing.size() + ") — each candidate path, and where it stopped");
        if (missing.isEmpty()) {
            out.append("  Nothing missing. Every path in the table resolved.\n");
        }
        for (Field field : missing) {
            out.append("  - ").append(field.label()).append('\n');
            for (String path : field.paths()) {
                out.append(trace(profileNode, path, field.collection()));
            }
        }

        section(out, "PAYLOAD KEYS — what the profile entity actually carries");
        out.append("  <root>\n").append(indentedKeys(profileNode));

        // The keys on the first element of each collection are where per-item paths (title,
        // companyName, dateRange...) are verified. Printing them saves opening the fixture.
        for (Field field : FIELDS) {
            if (!field.collection()) {
                continue;
            }
            firstElement(profileNode, field.paths()).ifPresent(element -> {
                out.append("  ").append(field.label()).append("[0]\n");
                out.append(indentedKeys(element));
            });
        }

        rule(out, "END " + fixture.getFileName());
        System.out.println(out);

        assertThat(profile.name().full())
                .as("a real capture should always yield a member name — see the report above")
                .isNotBlank();
    }

    // --- inspection helpers -------------------------------------------------------------

    private boolean isPopulated(JsonNode profileNode, Field field) {
        for (String path : field.paths()) {
            JsonNode node = walk(profileNode, path);
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (field.collection()) {
                JsonNode array = node.isArray() ? node : node.path("elements");
                if (array.isArray() && !array.isEmpty()) {
                    return true;
                }
            } else if (node.isValueNode() ? !node.asText().isBlank() : !node.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks a dotted path and describes exactly where it failed.
     *
     * <p>This is the line that turns "location is null" into "there is no {@code geo} under
     * {@code geoLocation}, but there is a {@code geoUrn} and a {@code preferredGeoPlace}" —
     * which names the fix without anyone opening the fixture.
     */
    private String trace(JsonNode root, String dotted, boolean collection) {
        JsonNode cur = root;
        StringBuilder walked = new StringBuilder();
        for (String segment : dotted.split("\\.")) {
            if (!cur.isObject()) {
                return "      %s  ->  '%s' is %s, cannot descend%n"
                        .formatted(dotted, walked, kind(cur));
            }
            JsonNode next = cur.path(segment);
            if (next.isMissingNode()) {
                // A root-level miss would otherwise reprint the entity's whole key list on
                // every line. On a real payload that is thirty keys times twenty misses and
                // nobody reads it. The full list is in PAYLOAD KEYS, once.
                if (walked.isEmpty()) {
                    return "      %s  ->  not on the profile entity (see PAYLOAD KEYS)%n"
                            .formatted(dotted);
                }
                return "      %s  ->  no '%s' under '%s'; available: %s%n"
                        .formatted(dotted, segment, walked, keyList(cur));
            }
            walked.append(walked.isEmpty() ? "" : ".").append(segment);
            cur = next;
        }
        if (collection) {
            JsonNode array = cur.isArray() ? cur : cur.path("elements");
            if (array.isArray()) {
                return "      %s  ->  present but empty (0 elements)%n".formatted(dotted);
            }
        }
        return "      %s  ->  present but unusable (%s)%n".formatted(dotted, kind(cur));
    }

    private Optional<JsonNode> firstElement(JsonNode root, List<String> paths) {
        for (String path : paths) {
            JsonNode node = walk(root, path);
            JsonNode array = node.isArray() ? node : node.path("elements");
            if (array.isArray() && !array.isEmpty()) {
                return Optional.of(array.get(0));
            }
        }
        return Optional.empty();
    }

    private JsonNode walk(JsonNode root, String dotted) {
        JsonNode cur = root;
        for (String segment : dotted.split("\\.")) {
            if (cur == null || !cur.isObject()) {
                return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
            cur = cur.path(segment);
        }
        return cur;
    }

    private String keyList(JsonNode node) {
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        if (keys.isEmpty()) {
            return "(no keys)";
        }
        if (keys.size() > 20) {
            return String.join(", ", keys.subList(0, 20)) + ", ... (+" + (keys.size() - 20) + ")";
        }
        return String.join(", ", keys);
    }

    /** Keys with a one-word type hint, so a nested object is visibly worth descending into. */
    private String indentedKeys(JsonNode node) {
        if (!node.isObject()) {
            return "      (" + kind(node) + ")\n";
        }
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.naturalOrder());
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append("      ").append(key)
               .append("  (").append(kind(node.path(key))).append(")\n");
        }
        return out.isEmpty() ? "      (no keys)\n" : out.toString();
    }

    private String kind(JsonNode node) {
        if (node.isArray()) {
            return "array[" + node.size() + "]";
        }
        if (node.isObject()) {
            return "object{" + node.size() + "}";
        }
        if (node.isNull()) {
            return "null";
        }
        if (node.isMissingNode()) {
            return "missing";
        }
        String text = node.asText();
        return text.length() > 40 ? "text:\"" + text.substring(0, 40) + "...\"" : "text:\"" + text + "\"";
    }

    private List<Path> realFixtures() throws IOException {
        if (!Files.isDirectory(FIXTURE_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(FIXTURE_DIR)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(REAL_FIXTURE_PREFIX) && name.endsWith(".json");
                    })
                    .sorted()
                    .toList();
        }
    }

    private void rule(StringBuilder out, String title) {
        out.append('\n').append("=".repeat(78)).append('\n')
           .append(title).append('\n')
           .append("=".repeat(78)).append('\n');
    }

    private void section(StringBuilder out, String title) {
        out.append('\n').append("-- ").append(title).append(' ')
           .append("-".repeat(Math.max(0, 74 - title.length()))).append('\n');
    }
}
