package com.sahil.linkedinapi.api;

import com.sahil.linkedinapi.acquisition.ProfileNotAccessibleException;
import com.sahil.linkedinapi.acquisition.ProfileNotFoundException;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;
import com.sahil.linkedinapi.application.ProfileService;
import com.sahil.linkedinapi.support.TestProfiles;
import com.sahil.linkedinapi.url.InvalidProfileUrlException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the HTTP contract: one status per failure mode, one error shape, and an envelope
 * whose meta block always says where the data came from.
 *
 * <p>Status mapping is the thing worth testing at this layer. A scraping API that answers
 * 500 for a private profile is lying to its callers, and that regression is invisible from
 * inside the service.
 */
@WebMvcTest(ProfileController.class)
class ProfileControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ProfileService service;

    private ProfileService.Result result(SourceType source, boolean cached, boolean stale) {
        return new ProfileService.Result(
                TestProfiles.of("ada-lovelace", "Ada", "Lovelace"),
                source, Instant.parse("2026-08-29T04:00:00Z"), cached, cached ? 120L : 0L,
                stale, 0.75, 812L);
    }

    @Test
    @DisplayName("200 returns meta beside profile, with provenance filled in")
    void returnsEnvelope() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenReturn(result(SourceType.VOYAGER, false, false));

        mvc.perform(get("/api/v1/profiles")
                        .param("url", "https://www.linkedin.com/in/ada-lovelace"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.meta.source").value("VOYAGER"))
                .andExpect(jsonPath("$.meta.cached").value(false))
                .andExpect(jsonPath("$.meta.stale").value(false))
                .andExpect(jsonPath("$.meta.completeness").value(0.75))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.profile.publicIdentifier").value("ada-lovelace"))
                .andExpect(jsonPath("$.profile.name.full").value("Ada Lovelace"))
                .andExpect(jsonPath("$.profile.experience[0].current").value(true));
    }

    @Test
    @DisplayName("a cached, stale answer says so rather than pretending to be fresh")
    void reportsStaleness() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenReturn(result(SourceType.CACHE, true, true));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/in/ada-lovelace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.source").value("CACHE"))
                .andExpect(jsonPath("$.meta.cached").value(true))
                .andExpect(jsonPath("$.meta.stale").value(true))
                .andExpect(jsonPath("$.meta.cacheAgeSeconds").value(120));
    }

    @Test
    @DisplayName("every key is present — absent collections serialize as [], not missing")
    void keysAreAlwaysPresent() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenReturn(result(SourceType.VOYAGER, false, false));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/in/ada-lovelace"))
                .andExpect(jsonPath("$.profile.skills").isArray())
                .andExpect(jsonPath("$.profile.certifications").isArray())
                .andExpect(jsonPath("$.profile.languages").isArray())
                .andExpect(jsonPath("$.profile.education").isArray())
                .andExpect(jsonPath("$.profile.flags").exists())
                .andExpect(jsonPath("$.profile.counts").exists())
                .andExpect(jsonPath("$.profile.about").exists());
    }

    @Test
    @DisplayName("400 with a named code for a URL that is not a member profile")
    void badUrlIs400() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenThrow(new InvalidProfileUrlException("That is a LinkedIn /company URL."));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/company/x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PROFILE_URL"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.error.requestId").exists());
    }

    @Test
    @DisplayName("400 when the url parameter is missing entirely")
    void missingParameterIs400() throws Exception {
        mvc.perform(get("/api/v1/profiles"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PROFILE_URL"));
    }

    @Test
    @DisplayName("404 when the member does not exist")
    void unknownMemberIs404() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenThrow(new ProfileNotFoundException("nobody"));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/in/nobody-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("422 — not 500 — when the profile exists but is walled off")
    void walledProfileIs422() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenThrow(new ProfileNotAccessibleException("Private, or every source hit a wall."));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/in/ada-lovelace"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PROFILE_NOT_ACCESSIBLE"));
    }

    @Test
    @DisplayName("503 carries a Retry-After so a client knows to back off")
    void upstreamDownIs503WithRetryAfter() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenThrow(new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "all sources failed",
                        60L, null));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/in/ada-lovelace"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    @DisplayName("an unexpected exception becomes a generic 500 that leaks nothing")
    void unexpectedErrorIsOpaque() throws Exception {
        when(service.get(anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("cookie li_at=SECRET blew up"));

        mvc.perform(get("/api/v1/profiles").param("url", "https://www.linkedin.com/in/ada-lovelace"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                // The internal message must not reach the client — it can carry cookies.
                .andExpect(jsonPath("$.error.message").value("An unexpected internal error occurred."));
    }

    @Test
    @DisplayName("204 on cache eviction")
    void evictionReturns204() throws Exception {
        when(service.toPublicIdentifier(anyString())).thenReturn("ada-lovelace");

        mvc.perform(delete("/api/v1/profiles/ada-lovelace/cache"))
                .andExpect(status().isNoContent());
    }
}
