package com.sahil.linkedinapi.acquisition.voyager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahil.linkedinapi.acquisition.ProfileNotFoundException;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.acquisition.SourceUnavailableException;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.session.LinkedInSession;
import com.sahil.linkedinapi.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Speaks LinkedIn's internal REST-li API.
 *
 * <p>Four things make this call work, and all four are non-obvious:
 * <ol>
 *   <li>The {@code csrf-token} header is the {@code JSESSIONID} cookie value with its
 *       surrounding quotes stripped. Same value, two forms, both required.</li>
 *   <li>{@code x-restli-protocol-version: 2.0.0} — without it the server answers in a
 *       different envelope shape.</li>
 *   <li>{@code accept: application/vnd.linkedin.normalized+json+2.1} is what asks for the
 *       flattened {@code included[]} graph. Ask for plain JSON and you get far less.</li>
 *   <li>A {@code referer} pointing at the profile page. Requests without one are treated
 *       markedly less kindly.</li>
 * </ol>
 *
 * <p>The response classification below is the important part. Every branch that quarantines
 * the session is a case where retrying makes things strictly worse: a checkpoint, a 401, a
 * 429 or a {@code 999} means LinkedIn has noticed us, and the correct response is to stop
 * using that session for a while, not to try again in 200ms.
 */
@Component
public class VoyagerClient {

    private static final Logger log = LoggerFactory.getLogger(VoyagerClient.class);

    private static final String PROFILE_PATH = "/voyager/api/identity/dash/profiles";
    /** LinkedIn's own non-standard "you are being throttled" status. */
    private static final int LINKEDIN_THROTTLED = 999;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final AppProperties props;
    private final SessionManager sessions;

    public VoyagerClient(HttpClient linkedInHttpClient, ObjectMapper mapper,
                         AppProperties props, SessionManager sessions) {
        this.http = linkedInHttpClient;
        this.mapper = mapper;
        this.props = props;
        this.sessions = sessions;
    }

    public JsonNode fetchProfileEnvelope(String publicIdentifier, Duration budget) {
        AppProperties.SourceSettings settings = props.sources().voyager();
        LinkedInSession session = sessions.checkout().orElseThrow(() ->
                new SourceUnavailableException(SourceType.VOYAGER,
                        "No LinkedIn session is available (all are cooling down, or none configured)."));

        URI uri = URI.create(settings.baseUrl() + PROFILE_PATH
                + "?q=memberIdentity"
                + "&memberIdentity=" + URLEncoder.encode(publicIdentifier, StandardCharsets.UTF_8)
                + "&decorationId=" + URLEncoder.encode(settings.decorationId(), StandardCharsets.UTF_8));

        Duration timeout = min(settings.timeout(), budget);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new SourceUnavailableException(SourceType.VOYAGER, "No budget left for a Voyager call.");
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("accept", "application/vnd.linkedin.normalized+json+2.1")
                .header("x-restli-protocol-version", "2.0.0")
                .header("csrf-token", session.csrfToken())
                .header("x-li-lang", "en_US")
                .header("accept-language", props.session().acceptLanguage())
                .header("user-agent", props.session().userAgent())
                .header("referer", settings.baseUrl() + "/in/" + publicIdentifier + "/")
                .header("cookie", session.cookieHeader())
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            sessions.recordFailure(session, "transport: " + e.getClass().getSimpleName());
            throw new SourceUnavailableException(SourceType.VOYAGER,
                    "Voyager request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceUnavailableException(SourceType.VOYAGER, "Voyager request interrupted.", e);
        }

        return classify(response, session, publicIdentifier);
    }

    private JsonNode classify(HttpResponse<String> response, LinkedInSession session, String slug) {
        int status = response.statusCode();

        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("location").orElse("");
            if (looksLikeAuthWall(location)) {
                sessions.quarantine(session, "redirected to auth wall / checkpoint");
                throw new SourceUnavailableException(SourceType.VOYAGER,
                        "Session rejected — LinkedIn redirected to an auth wall or checkpoint.");
            }
            throw new SourceUnavailableException(SourceType.VOYAGER,
                    "Unexpected redirect from Voyager (status " + status + ").");
        }

        switch (status) {
            case 401, 403 -> {
                sessions.quarantine(session, "HTTP " + status);
                throw new SourceUnavailableException(SourceType.VOYAGER,
                        "Session is not authorized (HTTP " + status + "). The cookie has likely expired.");
            }
            case 404 -> {
                // Authoritative: the member does not exist. The session is fine.
                sessions.recordSuccess(session);
                throw new ProfileNotFoundException(slug);
            }
            case 429, LINKEDIN_THROTTLED -> {
                sessions.quarantine(session, "HTTP " + status + " (throttled)");
                throw new SourceUnavailableException(SourceType.VOYAGER,
                        "LinkedIn is throttling this session (HTTP " + status + ").");
            }
            default -> {
                // fall through
            }
        }

        if (status != 200) {
            sessions.recordFailure(session, "HTTP " + status);
            throw new SourceUnavailableException(SourceType.VOYAGER,
                    "Voyager returned HTTP " + status + ".");
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            sessions.recordFailure(session, "empty body");
            throw new SourceUnavailableException(SourceType.VOYAGER, "Voyager returned an empty body.");
        }
        // A 200 carrying HTML is the login page wearing a success status.
        if (body.stripLeading().startsWith("<")) {
            sessions.quarantine(session, "HTML body on a 200 (login page)");
            throw new SourceUnavailableException(SourceType.VOYAGER,
                    "Voyager returned HTML instead of JSON — the session is no longer logged in.");
        }

        try {
            JsonNode envelope = mapper.readTree(body);
            sessions.recordSuccess(session);
            log.debug("Voyager returned {} included entities for {}",
                    envelope.path("included").size(), slug);
            return envelope;
        } catch (Exception e) {
            sessions.recordFailure(session, "unparseable JSON");
            throw new SourceUnavailableException(SourceType.VOYAGER,
                    "Could not parse the Voyager response as JSON.", e);
        }
    }

    private boolean looksLikeAuthWall(String location) {
        String value = location.toLowerCase();
        return value.contains("authwall") || value.contains("checkpoint")
                || value.contains("/login") || value.contains("challenge");
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
