package com.sahil.linkedinapi.domain;

public record Certification(
        String name,
        String authority,
        String authorityLogo,
        String credentialId,
        String url,
        String issuedDate,
        String expirationDate
) {
}
