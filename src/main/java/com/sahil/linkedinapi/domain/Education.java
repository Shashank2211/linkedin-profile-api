package com.sahil.linkedinapi.domain;

public record Education(
        String school,
        String schoolUrn,
        String schoolLogo,
        String degree,
        String fieldOfStudy,
        String startDate,
        String endDate,
        String grade,
        String activities,
        String description
) {
}
