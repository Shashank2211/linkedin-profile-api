package com.sahil.linkedinapi.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahil.linkedinapi.domain.Certification;
import com.sahil.linkedinapi.domain.Education;
import com.sahil.linkedinapi.domain.Experience;
import com.sahil.linkedinapi.domain.LanguageEntry;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.domain.Skill;
import com.sahil.linkedinapi.support.Dates;
import com.sahil.linkedinapi.support.Json;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects a resolved Voyager profile graph onto the API's own contract.
 *
 * <p>Every read goes through {@link Json}'s multi-path accessors. That is not laziness
 * about the payload shape — it is the mitigation for the repo's biggest known risk. Field
 * locations move between decoration versions, and a mapper that tries three known homes
 * for a headline keeps working through a version bump that would leave a single-path
 * mapper returning a profile full of nulls.
 *
 * <p>The mapper never throws on a missing field. A partial profile plus an honest
 * completeness score beats an exception, because the caller can still use the half we got.
 */
@Component
public class VoyagerProfileMapper {

    public Profile map(JsonNode profile, String publicIdentifier, String profileUrl) {
        return new Profile(
                Json.text(profile, "publicIdentifier") != null
                        ? Json.text(profile, "publicIdentifier") : publicIdentifier,
                profileUrl,
                Json.text(profile, "entityUrn"),
                Profile.Name.of(Json.text(profile, "firstName"), Json.text(profile, "lastName")),
                Json.text(profile, "headline"),
                Json.text(profile, "summary", "about"),
                location(profile),
                Json.text(profile, "industry.name", "industryV2.name", "industryName"),
                ImageMapper.from(profile, "profilePicture", "picture"),
                ImageMapper.from(profile, "backgroundPicture", "backgroundImage"),
                flags(profile),
                counts(profile),
                experience(profile),
                education(profile),
                skills(profile),
                certifications(profile),
                languages(profile));
    }

    private Profile.Location location(JsonNode profile) {
        String raw = Json.text(profile,
                "geoLocation.geo.defaultLocalizedName",
                "geoLocationName",
                "locationName");
        String country = Json.text(profile,
                "geoLocation.geo.country.countryCode",
                "location.basicLocation.countryCode",
                "geoCountry.countryCode");
        // City is only set when LinkedIn hands it to us directly. We do not split the raw
        // string on a comma and hope — a confidently wrong city is worse than a null one.
        String city = Json.text(profile, "geoLocation.geo.defaultLocalizedNameWithoutCountryName");
        if (raw == null && country == null && city == null) {
            return Profile.Location.EMPTY;
        }
        return new Profile.Location(raw, city, country);
    }

    private Profile.Flags flags(JsonNode profile) {
        return new Profile.Flags(
                Json.bool(profile, "memberBadges.openToWork", "openToWork"),
                Json.bool(profile, "memberBadges.premium", "premium"),
                Json.bool(profile, "memberBadges.influencer", "influencer"));
    }

    private Profile.Counts counts(JsonNode profile) {
        return new Profile.Counts(
                Json.integer(profile, "connections.paging.total", "connectionsCount"),
                Json.integer(profile, "followingState.followerCount", "followersCount"));
    }

    /**
     * Flattens position groups into a plain role list.
     *
     * <p>LinkedIn nests roles inside a "position group" per company so the UI can render
     * three promotions at one employer as a single card. Reading only the group level is
     * the classic mistake: you get one entry per company and silently lose every
     * promotion. We walk into each group and emit every role, falling back to the group
     * itself when a group carries no inner positions.
     */
    private List<Experience> experience(JsonNode profile) {
        List<Experience> out = new ArrayList<>();
        List<JsonNode> groups = Json.elementsAt(profile,
                "profilePositionGroups", "positionGroups", "positionView");
        for (JsonNode group : groups) {
            List<JsonNode> positions = Json.elementsAt(group,
                    "profilePositionInPositionGroup", "positions");
            if (positions.isEmpty()) {
                out.add(toExperience(group, group));
            } else {
                for (JsonNode position : positions) {
                    out.add(toExperience(position, group));
                }
            }
        }
        if (out.isEmpty()) {
            // Some decorations return a flat position list with no grouping at all.
            for (JsonNode position : Json.elementsAt(profile, "profilePositions", "positions")) {
                out.add(toExperience(position, position));
            }
        }
        return List.copyOf(out);
    }

    private Experience toExperience(JsonNode position, JsonNode group) {
        JsonNode dateRange = firstPresent(position, group, "dateRange", "timePeriod");
        String start = Dates.start(dateRange);
        String end = Dates.end(dateRange);

        String companyName = Json.text(position, "companyName", "company.name")
                != null ? Json.text(position, "companyName", "company.name")
                : Json.text(group, "companyName", "company.name");

        Experience.Company company = new Experience.Company(
                companyName,
                Json.text(position, "company.entityUrn", "companyUrn"),
                pickCompanyLogo(position, group),
                Json.text(position, "company.url", "companyUrl"));

        return new Experience(
                Json.text(position, "title", "name"),
                company,
                Enums.screamingSnake(Json.text(position, "employmentType.name", "employmentType",
                        "employmentTypeUrn")),
                Json.text(position, "locationName", "location.name", "geoLocationName"),
                Enums.screamingSnake(Json.text(position, "workplaceType.name", "workplaceType",
                        "locationType")),
                start,
                end,
                start != null ? end == null : null,
                Json.text(position, "description"),
                List.of());
    }

    private String pickCompanyLogo(JsonNode position, JsonNode group) {
        var fromPosition = ImageMapper.from(position, "company.logo", "companyLogo", "logo");
        if (fromPosition != null) {
            return fromPosition.url();
        }
        var fromGroup = ImageMapper.from(group, "company.logo", "companyLogo", "logo");
        return fromGroup == null ? null : fromGroup.url();
    }

    private JsonNode firstPresent(JsonNode first, JsonNode second, String... paths) {
        for (String path : paths) {
            JsonNode found = Json.at(first, path);
            if (found.isObject()) {
                return found;
            }
        }
        for (String path : paths) {
            JsonNode found = Json.at(second, path);
            if (found.isObject()) {
                return found;
            }
        }
        return Json.at(first, paths.length > 0 ? paths[0] : "dateRange");
    }

    private List<Education> education(JsonNode profile) {
        List<Education> out = new ArrayList<>();
        for (JsonNode node : Json.elementsAt(profile, "profileEducations", "educations", "educationView")) {
            JsonNode dateRange = Json.at(node, "dateRange").isObject()
                    ? Json.at(node, "dateRange") : Json.at(node, "timePeriod");
            var logo = ImageMapper.from(node, "school.logo", "schoolLogo", "logo");
            out.add(new Education(
                    Json.text(node, "schoolName", "school.name"),
                    Json.text(node, "school.entityUrn", "schoolUrn"),
                    logo == null ? null : logo.url(),
                    Json.text(node, "degreeName", "degree"),
                    Json.text(node, "fieldOfStudy"),
                    Dates.start(dateRange),
                    Dates.end(dateRange),
                    Json.text(node, "grade"),
                    Json.text(node, "activities"),
                    Json.text(node, "description")));
        }
        return List.copyOf(out);
    }

    private List<Skill> skills(JsonNode profile) {
        List<Skill> out = new ArrayList<>();
        for (JsonNode node : Json.elementsAt(profile, "profileSkills", "skills", "skillView")) {
            String name = Json.text(node, "name", "skill.name");
            if (name != null) {
                out.add(new Skill(name,
                        Json.integer(node, "endorsementCount", "endorsedCount",
                                "insights.endorsementCount")));
            }
        }
        return List.copyOf(out);
    }

    private List<Certification> certifications(JsonNode profile) {
        List<Certification> out = new ArrayList<>();
        for (JsonNode node : Json.elementsAt(profile, "profileCertifications", "certifications")) {
            JsonNode dateRange = Json.at(node, "dateRange").isObject()
                    ? Json.at(node, "dateRange") : Json.at(node, "timePeriod");
            var logo = ImageMapper.from(node, "company.logo", "authorityLogo", "logo");
            out.add(new Certification(
                    Json.text(node, "name"),
                    Json.text(node, "authority", "company.name"),
                    logo == null ? null : logo.url(),
                    Json.text(node, "licenseNumber", "credentialId"),
                    Json.text(node, "url"),
                    Dates.start(dateRange),
                    Dates.end(dateRange)));
        }
        return List.copyOf(out);
    }

    private List<LanguageEntry> languages(JsonNode profile) {
        List<LanguageEntry> out = new ArrayList<>();
        for (JsonNode node : Json.elementsAt(profile, "profileLanguages", "languages")) {
            String name = Json.text(node, "name");
            if (name != null) {
                out.add(new LanguageEntry(name,
                        Enums.screamingSnake(Json.text(node, "proficiency"))));
            }
        }
        return List.copyOf(out);
    }
}
