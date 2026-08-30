package com.sahil.linkedinapi.domain;

import java.util.List;

/**
 * One role. LinkedIn nests roles inside "position groups" so it can render several
 * promotions at the same company as a single card; we flatten that, because a client
 * asking for work history wants every role, not every employer.
 */
public record Experience(
        String title,
        Company company,
        String employmentType,
        String location,
        String locationType,
        String startDate,
        String endDate,
        Boolean current,
        String description,
        List<String> skills
) {

    public record Company(String name, String urn, String logo, String url) {
        public static final Company EMPTY = new Company(null, null, null, null);
    }
}
