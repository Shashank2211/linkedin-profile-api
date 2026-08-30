package com.sahil.linkedinapi.api.dto;

import com.sahil.linkedinapi.domain.Profile;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The single success shape: metadata beside the data, never mixed into it.
 *
 * <p>Keeping {@code meta} out of {@code profile} means the profile object is exactly the
 * member's data and nothing else — a caller can persist it, diff it, or hand it to a
 * consumer without first stripping our bookkeeping out of it.
 */
@Schema(description = "A profile plus the provenance of the data in it.")
public record ProfileEnvelope(ResponseMeta meta, Profile profile) {
}
