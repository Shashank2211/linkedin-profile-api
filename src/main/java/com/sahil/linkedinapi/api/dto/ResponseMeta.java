package com.sahil.linkedinapi.api.dto;

import com.sahil.linkedinapi.acquisition.SourceType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Provenance, attached to every successful response.
 *
 * <p>Most scraping APIs return the data and nothing else, which forces the caller to treat
 * every null as ambiguous. This block removes the ambiguity: it says where the data came
 * from, how old it is, and how much of the profile we managed to read.
 */
@Schema(description = "How this response was obtained. Read it before trusting a null.")
public record ResponseMeta(

        @Schema(description = "Correlates with the X-Request-Id header and the server logs.")
        String requestId,

        @Schema(description = "When the underlying LinkedIn fetch happened — not when this "
                + "response was serialized. For a cached hit this is the original fetch time.")
        Instant fetchedAt,

        @Schema(description = "VOYAGER = authenticated internal API (richest). "
                + "PUBLIC_HTML = logged-out page (thin). CACHE = served from memory.")
        SourceType source,

        @Schema(description = "True when no LinkedIn request was made for this response.")
        boolean cached,

        @Schema(description = "Age of the cached copy in seconds. 0 for a fresh fetch.")
        long cacheAgeSeconds,

        @Schema(description = "True when the upstream was unreachable and we served a copy "
                + "older than the freshness window rather than failing.")
        boolean stale,

        @Schema(description = "Fraction of the profile we could read, 0.0–1.0. A low score "
                + "next to empty arrays means 'could not see', not 'has none'.")
        double completeness,

        @Schema(description = "Server-side wall-clock time for this request.")
        long durationMs
) {
}
