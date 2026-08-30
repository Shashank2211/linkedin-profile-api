import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips personal data out of a captured Voyager response while leaving its structure
 * completely intact, so the result is safe to commit as a test fixture.
 *
 * <p>Single-file, JDK-only, no build step:
 *
 * <pre>
 *   java tools/FixtureRedactor.java raw.json src/test/resources/fixtures/voyager-real.json
 * </pre>
 *
 * <p><strong>What it preserves</strong>, because these are what the mappers are tested
 * against: every key name, the {@code included[]} / {@code *pointer} shape, array lengths,
 * nesting depth, date objects, image dimensions, {@code $type} discriminators, and the
 * <em>internal consistency</em> of member URNs — a member id is remapped to the same
 * placeholder everywhere it appears, so the URN graph still resolves after redaction.
 *
 * <p><strong>What it removes:</strong> names, identifiers, free text, locations,
 * organisation names, credential numbers, and every media URL.
 *
 * <p>It finishes with a residual scan for anything that looks like a leaked credential or
 * contact detail and exits non-zero if it finds one. Read the report before committing —
 * this tool is a first pass, not a guarantee.
 */
public final class FixtureRedactor {

    /** Keys replaced with a fixed placeholder. Order does not matter. */
    private static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();

    static {
        // Identity
        REPLACEMENTS.put("firstName", "Ada");
        REPLACEMENTS.put("lastName", "Lovelace");
        REPLACEMENTS.put("publicIdentifier", "member-one");
        REPLACEMENTS.put("maidenName", "");
        REPLACEMENTS.put("memberDistance", "DISTANCE_1");

        // Free text — the richest source of incidental personal detail
        REPLACEMENTS.put("headline", "[redacted headline]");
        REPLACEMENTS.put("summary", "[redacted about section]");
        REPLACEMENTS.put("description", "[redacted description]");
        REPLACEMENTS.put("activities", "[redacted activities]");
        REPLACEMENTS.put("grade", "[redacted]");
        REPLACEMENTS.put("occupation", "[redacted occupation]");

        // Organisations and roles — individually innocuous, jointly identifying
        REPLACEMENTS.put("companyName", "Example Organisation");
        REPLACEMENTS.put("schoolName", "Example University");
        REPLACEMENTS.put("authority", "Example Authority");
        // "name" is handled separately — see redactNames.
        REPLACEMENTS.put("title", "Example Job Title");
        REPLACEMENTS.put("degreeName", "Example Degree");
        REPLACEMENTS.put("fieldOfStudy", "Example Field");
        REPLACEMENTS.put("industryName", "Example Industry");

        // Location
        REPLACEMENTS.put("defaultLocalizedName", "Example City, Example Region, Country");
        REPLACEMENTS.put("defaultLocalizedNameWithoutCountryName", "Example City");
        REPLACEMENTS.put("geoLocationName", "Example City, Country");
        REPLACEMENTS.put("locationName", "Example City, Country");
        REPLACEMENTS.put("postalCode", "00000");

        // Credentials and contact
        REPLACEMENTS.put("licenseNumber", "REDACTED-ID");
        REPLACEMENTS.put("credentialId", "REDACTED-ID");
        REPLACEMENTS.put("emailAddress", "redacted@example.com");
        REPLACEMENTS.put("phoneNumber", "+00000000000");
        REPLACEMENTS.put("address", "[redacted address]");
        REPLACEMENTS.put("birthDateOn", "");

        // Media — signed URLs carry an account fingerprint and expire anyway
        REPLACEMENTS.put("rootUrl", "https://media.example.com/img/");
        REPLACEMENTS.put("url", "https://example.com/redacted");
        REPLACEMENTS.put("navigationUrl", "https://example.com/redacted");
        REPLACEMENTS.put("profileUrl", "https://www.linkedin.com/in/member-one");
    }

    /**
     * Values of a {@code "name"} field that are vocabulary, not personal data.
     *
     * <p>{@code employmentType.name} and {@code workplaceType.name} both live under the
     * generic key {@code name}. Blanket-redacting it would strip the enum vocabulary out of
     * the fixture and leave {@code Enums.screamingSnake} untested, so these pass through
     * while every other {@code name} — company, school, certifying body — is replaced.
     */
    private static final List<String> SAFE_NAME_VALUES = List.of(
            "full-time", "part-time", "self-employed", "freelance", "contract",
            "internship", "apprenticeship", "seasonal", "temporary",
            "on-site", "hybrid", "remote");

    /** Anything matching these after redaction is a bug, not a fixture. */
    private static final Map<String, Pattern> RESIDUAL_CHECKS = Map.of(
            "LinkedIn session cookie", Pattern.compile("li_at", Pattern.CASE_INSENSITIVE),
            "JSESSIONID / CSRF token", Pattern.compile("JSESSIONID|csrf-?token|\"ajax:", Pattern.CASE_INSENSITIVE),
            "email address", Pattern.compile("[A-Za-z0-9._%+-]+@(?!example\\.com)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            "licdn media URL", Pattern.compile("media(-exp\\d)?\\.licdn\\.com"),
            "unmapped member id", Pattern.compile("ACoA(?!AA_MEMBER)[A-Za-z0-9_-]{8,}")
    );

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: java tools/FixtureRedactor.java <input.json> <output.json>");
            System.exit(2);
            return;
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);

        String json = Files.readString(in, StandardCharsets.UTF_8);
        int originalLength = json.length();

        json = remapMemberIds(json);
        int fieldsRedacted = 0;
        for (Map.Entry<String, String> entry : REPLACEMENTS.entrySet()) {
            Result result = redactStringValues(json, entry.getKey(), entry.getValue());
            json = result.json();
            fieldsRedacted += result.count();
        }
        Result names = redactNames(json);
        json = names.json();
        fieldsRedacted += names.count();

        Result images = redactImageSegments(json);
        json = images.json();

        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);

        System.out.printf("Read      %s (%,d chars)%n", in, originalLength);
        System.out.printf("Wrote     %s (%,d chars)%n", out, json.length());
        System.out.printf("Redacted  %d string fields, %d image path segments%n",
                fieldsRedacted, images.count());

        List<String> problems = residualScan(json);
        if (problems.isEmpty()) {
            System.out.println("Residual scan clean. Read the file yourself before committing;"
                    + " this tool is a first pass, not a guarantee.");
        } else {
            System.err.println();
            System.err.println("RESIDUAL SCAN FAILED - do not commit this file:");
            problems.forEach(p -> System.err.println("  - " + p));
            System.exit(1);
        }
    }

    private record Result(String json, int count) {
    }

    /**
     * Member ids appear inside many URNs and must stay internally consistent, or the URN
     * graph no longer resolves and the fixture tests nothing. Each distinct id gets its own
     * stable placeholder.
     */
    private static String remapMemberIds(String json) {
        Pattern memberId = Pattern.compile("ACoA[A-Za-z0-9_-]{8,}");
        Map<String, String> seen = new LinkedHashMap<>();
        Matcher matcher = memberId.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = seen.computeIfAbsent(matcher.group(),
                    k -> "ACoAAA_MEMBER_" + (seen.size() + 1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        if (!seen.isEmpty()) {
            System.out.printf("Remapped  %d distinct member id(s)%n", seen.size());
        }
        return sb.toString();
    }

    /** Replaces the value of every {@code "key": "..."} pair, escapes included. */
    private static Result redactStringValues(String json, String key, String replacement) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"");
        Matcher matcher = pattern.matcher(json);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement("\"" + key + "\": \"" + replacement + "\""));
            count++;
        }
        matcher.appendTail(sb);
        return new Result(sb.toString(), count);
    }

    /** Replaces every {@code "name"} value except the enum vocabulary listed above. */
    private static Result redactNames(String json) {
        Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            String value = matcher.group(1);
            if (SAFE_NAME_VALUES.contains(value.trim().toLowerCase())) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(sb,
                    Matcher.quoteReplacement("\"name\": \"Example Organisation\""));
            count++;
        }
        matcher.appendTail(sb);
        return new Result(sb.toString(), count);
    }

    /**
     * Image path segments keep their size prefix ({@code 400_400/}) so
     * {@code ImageMapper} still sees a realistic set of variants to sort.
     */
    private static Result redactImageSegments(String json) {
        Pattern pattern = Pattern.compile(
                "\"fileIdentifyingUrlPathSegment\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher matcher = pattern.matcher(json);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            String value = matcher.group(1);
            Matcher size = Pattern.compile("^(\\d+_\\d+/)").matcher(value);
            String prefix = size.find() ? size.group(1) : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    "\"fileIdentifyingUrlPathSegment\": \"" + prefix + "redacted.jpg\""));
            count++;
        }
        matcher.appendTail(sb);
        return new Result(sb.toString(), count);
    }

    private static List<String> residualScan(String json) {
        return RESIDUAL_CHECKS.entrySet().stream()
                .filter(check -> check.getValue().matcher(json).find())
                .map(check -> check.getKey() + " still present")
                .sorted()
                .toList();
    }
}
