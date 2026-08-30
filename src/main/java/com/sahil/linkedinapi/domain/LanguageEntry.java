package com.sahil.linkedinapi.domain;

/**
 * Named {@code LanguageEntry} rather than {@code Language} to avoid colliding with
 * {@link java.lang.Character} / locale types in readers' heads. Serialized under the
 * {@code languages} key.
 */
public record LanguageEntry(String name, String proficiency) {
}
