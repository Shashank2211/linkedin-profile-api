package com.sahil.linkedinapi.acquisition.publichtml;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahil.linkedinapi.domain.Education;
import com.sahil.linkedinapi.domain.Experience;
import com.sahil.linkedinapi.domain.ImageAsset;
import com.sahil.linkedinapi.domain.LanguageEntry;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.support.Json;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a schema.org {@code Person} block onto the same contract the Voyager mapper produces.
 *
 * <p>The whole point of the layered design lives here: two completely different acquisition
 * paths, one output shape. A caller reading a public-page result uses exactly the same
 * parsing code as one reading a Voyager result; only {@code meta.source} and
 * {@code meta.completeness} tell them the second is thinner.
 *
 * <p>What JSON-LD gives us: name, headline, location, image, current employers, schools and
 * languages. What it does not: the about section, dates on roles, skills, certifications and
 * endorsement counts. Those come back null, and the completeness score says so.
 */
@Component
public class JsonLdProfileMapper {

    public Profile map(JsonNode person, String publicIdentifier, String profileUrl) {
        String fullName = Json.text(person, "name");
        return new Profile(
                publicIdentifier,
                profileUrl,
                null,
                splitName(fullName),
                headline(person),
                Json.text(person, "description"),
                location(person),
                null,
                image(person),
                null,
                Profile.Flags.EMPTY,
                Profile.Counts.EMPTY,
                experience(person),
                education(person),
                List.of(),
                List.of(),
                languages(person));
    }

    /**
     * JSON-LD gives one {@code name} string. We split on the last space — good enough for
     * most Latin-script names, wrong for many others, and the {@code full} field is always
     * populated so a caller who cares can ignore the split entirely.
     */
    private Profile.Name splitName(String fullName) {
        String full = Json.blankToNull(fullName);
        if (full == null) {
            return new Profile.Name(null, null, null);
        }
        int lastSpace = full.lastIndexOf(' ');
        if (lastSpace <= 0) {
            return new Profile.Name(full, null, full);
        }
        return new Profile.Name(full.substring(0, lastSpace).trim(),
                full.substring(lastSpace + 1).trim(), full);
    }

    private String headline(JsonNode person) {
        JsonNode jobTitle = person.path("jobTitle");
        if (jobTitle.isTextual()) {
            return Json.blankToNull(jobTitle.asText());
        }
        if (jobTitle.isArray() && !jobTitle.isEmpty()) {
            return Json.blankToNull(jobTitle.get(0).asText(null));
        }
        return Json.text(person, "disambiguatingDescription");
    }

    private Profile.Location location(JsonNode person) {
        String locality = Json.text(person, "address.addressLocality");
        String region = Json.text(person, "address.addressRegion");
        String country = Json.text(person, "address.addressCountry");
        if (locality == null && region == null && country == null) {
            return Profile.Location.EMPTY;
        }
        String raw = String.join(", ", java.util.stream.Stream.of(locality, region, country)
                .filter(java.util.Objects::nonNull).toList());
        return new Profile.Location(Json.blankToNull(raw), locality, country);
    }

    private ImageAsset image(JsonNode person) {
        String url = Json.text(person, "image.contentUrl", "image.url", "image");
        if (url == null) {
            return null;
        }
        return new ImageAsset(url, List.of(new ImageAsset.Variant(null, null, url)));
    }

    private List<Experience> experience(JsonNode person) {
        List<Experience> out = new ArrayList<>();
        JsonNode worksFor = person.path("worksFor");
        if (worksFor.isArray()) {
            for (JsonNode org : worksFor) {
                String name = Json.text(org, "name");
                if (name == null) {
                    continue;
                }
                out.add(new Experience(
                        Json.text(org, "member.description", "description"),
                        new Experience.Company(name, null, Json.text(org, "logo"), Json.text(org, "url")),
                        null, null, null,
                        Json.text(org, "member.startDate"),
                        Json.text(org, "member.endDate"),
                        // worksFor is present tense by definition in this block.
                        Boolean.TRUE,
                        null,
                        List.of()));
            }
        }
        return List.copyOf(out);
    }

    private List<Education> education(JsonNode person) {
        List<Education> out = new ArrayList<>();
        JsonNode alumniOf = person.path("alumniOf");
        if (alumniOf.isArray()) {
            for (JsonNode school : alumniOf) {
                String name = Json.text(school, "name");
                if (name == null) {
                    continue;
                }
                out.add(new Education(name, null, Json.text(school, "logo"),
                        null, null,
                        Json.text(school, "member.startDate"),
                        Json.text(school, "member.endDate"),
                        null, null, null));
            }
        }
        return List.copyOf(out);
    }

    private List<LanguageEntry> languages(JsonNode person) {
        List<LanguageEntry> out = new ArrayList<>();
        JsonNode knows = person.path("knowsLanguage");
        if (knows.isArray()) {
            for (JsonNode language : knows) {
                String name = language.isTextual() ? language.asText() : Json.text(language, "name");
                if (Json.blankToNull(name) != null) {
                    out.add(new LanguageEntry(name.trim(), null));
                }
            }
        }
        return List.copyOf(out);
    }
}
