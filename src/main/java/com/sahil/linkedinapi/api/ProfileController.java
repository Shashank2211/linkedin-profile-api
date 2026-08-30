package com.sahil.linkedinapi.api;

import com.sahil.linkedinapi.api.dto.ProfileEnvelope;
import com.sahil.linkedinapi.api.dto.ResponseMeta;
import com.sahil.linkedinapi.api.error.ErrorResponse;
import com.sahil.linkedinapi.application.ProfileService;
import com.sahil.linkedinapi.config.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Profiles", description = "Resolve a LinkedIn profile URL into structured JSON.")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    /**
     * The URL arrives as a query parameter rather than a path segment. Profile identifiers
     * contain unicode and the URL itself contains slashes and colons; a query parameter
     * keeps the encoding rules boring, which is what you want on the one input a caller
     * always gets slightly wrong.
     */
    @GetMapping("/profiles")
    @Operation(
            summary = "Fetch a profile by its LinkedIn URL",
            description = """
                    Accepts anything that identifies a member profile — a full URL, a
                    locale subdomain (`in.linkedin.com`), a tracking-parameter-laden copy
                    from the address bar, or a bare `linkedin.com/in/<id>`.

                    The URL is reduced to a public identifier before anything else happens;
                    only that identifier is ever used to build an outbound request.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile resolved (possibly from cache)"),
            @ApiResponse(responseCode = "400", description = "Not a LinkedIn member profile URL",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such member",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Profile exists but is not visible",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limited",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Every source is unavailable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<ProfileEnvelope> getProfile(
            @Parameter(description = "LinkedIn profile URL", required = true,
                    example = "https://www.linkedin.com/in/williamhgates")
            @RequestParam("url") String url,

            @Parameter(description = "Bypass the freshness window and force an upstream fetch. "
                    + "Use sparingly — it spends the LinkedIn rate budget.")
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {

        ProfileService.Result result = service.get(url, refresh);

        var meta = new ResponseMeta(
                MDC.get(RequestIdFilter.MDC_KEY),
                result.fetchedAt(),
                result.source(),
                result.cached(),
                result.cacheAgeSeconds(),
                result.stale(),
                result.completeness(),
                result.durationMs());

        return ResponseEntity.ok()
                .header("Cache-Control", "private, max-age=300")
                .body(new ProfileEnvelope(meta, result.profile()));
    }

    /**
     * Data-deletion hook. Profile data is personal data, and a service that holds it — even
     * in memory, even briefly — should be able to drop a specific person on request without
     * a redeploy.
     */
    @DeleteMapping("/profiles/{publicIdentifier}/cache")
    @Operation(summary = "Evict one profile from the cache",
            description = "Removes any cached copy of this member. Always succeeds, whether or "
                    + "not a copy was held.")
    public ResponseEntity<Void> evict(@PathVariable String publicIdentifier) {
        service.evict(service.toPublicIdentifier(publicIdentifier));
        return ResponseEntity.noContent().build();
    }
}
