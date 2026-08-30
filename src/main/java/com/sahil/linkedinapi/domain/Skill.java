package com.sahil.linkedinapi.domain;

/**
 * {@code endorsements} is null rather than 0 when the count was not in the payload —
 * "nobody endorsed this" and "we could not see the endorsement count" are different
 * facts and the schema keeps them apart.
 */
public record Skill(String name, Integer endorsements) {
}
