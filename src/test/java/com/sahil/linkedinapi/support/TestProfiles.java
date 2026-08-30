package com.sahil.linkedinapi.support;

import com.sahil.linkedinapi.domain.Experience;
import com.sahil.linkedinapi.domain.Profile;

import java.util.List;

/** Small, readable {@link Profile} instances for tests that do not need a full fixture. */
public final class TestProfiles {

    private TestProfiles() {
    }

    public static Profile of(String publicIdentifier, String firstName, String lastName) {
        return new Profile(
                publicIdentifier,
                "https://www.linkedin.com/in/" + publicIdentifier,
                "urn:li:fsd_profile:" + publicIdentifier.toUpperCase(),
                Profile.Name.of(firstName, lastName),
                "Test headline",
                "Test about section.",
                new Profile.Location("Pune, Maharashtra, India", null, "in"),
                "Software Development",
                null,
                null,
                Profile.Flags.EMPTY,
                Profile.Counts.EMPTY,
                List.of(new Experience("Engineer", Experience.Company.EMPTY,
                        "FULL_TIME", null, null, "2024-01", null, true, null, List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
