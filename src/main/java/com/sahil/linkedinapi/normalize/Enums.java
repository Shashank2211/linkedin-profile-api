package com.sahil.linkedinapi.normalize;

import com.sahil.linkedinapi.support.Json;

import java.util.Locale;

/**
 * Normalizes LinkedIn's free-form labels into closed, screaming-snake enum values.
 *
 * <p>Deliberately returns a {@code String} rather than a Java enum. A new employment type
 * appearing in the payload must not be able to 500 the mapper — the contract documents the
 * values we know about and says explicitly that unrecognized ones pass through normalized.
 * A closed Java enum here would trade a small typing win for an outage.
 */
public final class Enums {

    private Enums() {
    }

    /** {@code "Full-time"} → {@code "FULL_TIME"}; a bare URN → null. */
    public static String screamingSnake(String raw) {
        String value = Json.blankToNull(raw);
        if (value == null) {
            return null;
        }
        // A raw URN carries no human meaning — better null than "URN_LI_FSD_EMPLOYMENTTYPE_1".
        if (value.startsWith("urn:li:")) {
            return null;
        }
        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? null : normalized;
    }
}
