package com.sahil.linkedinapi.domain;

import java.util.List;

/**
 * The canonical profile contract returned by this API.
 *
 * <p>Contract rules (also stated in the README, and enforced by
 * {@code spring.jackson.default-property-inclusion: always}):
 * <ul>
 *   <li>Every key is always present. Missing scalars serialize as {@code null}.</li>
 *   <li>Missing collections serialize as {@code []}, never {@code null}.</li>
 *   <li>{@code null} means "we could not read this"; {@code []} means "there is none".</li>
 *   <li>Dates are ISO-8601 partials ({@code "2024"}, {@code "2024-06"}) — LinkedIn
 *       rarely gives a day, so we never invent one.</li>
 * </ul>
 */
public record Profile(
        String publicIdentifier,
        String profileUrl,
        String urn,
        Name name,
        String headline,
        String about,
        Location location,
        String industry,
        ImageAsset profilePicture,
        ImageAsset backgroundImage,
        Flags flags,
        Counts counts,
        List<Experience> experience,
        List<Education> education,
        List<Skill> skills,
        List<Certification> certifications,
        List<LanguageEntry> languages
) {

    public record Name(String first, String last, String full) {
        public static Name of(String first, String last) {
            String f = first == null ? "" : first.trim();
            String l = last == null ? "" : last.trim();
            String full = (f + " " + l).trim();
            return new Name(emptyToNull(f), emptyToNull(l), emptyToNull(full));
        }

        private static String emptyToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    /**
     * {@code raw} is exactly what LinkedIn rendered. {@code city} and {@code country}
     * are only populated when they can be read directly from the payload — we do not
     * geocode or guess, because a wrong-but-confident location is worse than a null.
     */
    public record Location(String raw, String city, String country) {
        public static final Location EMPTY = new Location(null, null, null);
    }

    public record Flags(Boolean openToWork, Boolean premium, Boolean influencer) {
        public static final Flags EMPTY = new Flags(null, null, null);
    }

    public record Counts(Integer connections, Integer followers) {
        public static final Counts EMPTY = new Counts(null, null);
    }
}
