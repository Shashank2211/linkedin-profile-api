package com.sahil.linkedinapi.acquisition.voyager;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahil.linkedinapi.acquisition.PaceGate;
import com.sahil.linkedinapi.acquisition.ProfileSource;
import com.sahil.linkedinapi.acquisition.SourceType;
import com.sahil.linkedinapi.acquisition.SourceUnavailableException;
import com.sahil.linkedinapi.config.AppProperties;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.normalize.UrnGraph;
import com.sahil.linkedinapi.normalize.VoyagerProfileMapper;
import com.sahil.linkedinapi.session.SessionManager;
import com.sahil.linkedinapi.url.ProfileUrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Primary source: authenticated Voyager call, URN graph resolution, then mapping. */
@Component
@Order(1)
public class VoyagerProfileSource implements ProfileSource {

    private static final Logger log = LoggerFactory.getLogger(VoyagerProfileSource.class);

    private final VoyagerClient client;
    private final VoyagerProfileMapper mapper;
    private final SessionManager sessions;
    private final ProfileUrlParser urls;
    private final AppProperties.SourceSettings settings;
    private final PaceGate pace;

    public VoyagerProfileSource(VoyagerClient client, VoyagerProfileMapper mapper,
                                SessionManager sessions, ProfileUrlParser urls, AppProperties props) {
        this.client = client;
        this.mapper = mapper;
        this.sessions = sessions;
        this.urls = urls;
        this.settings = props.sources().voyager();
        this.pace = new PaceGate(settings.minInterval(), settings.jitter());
    }

    @Override
    public SourceType type() {
        return SourceType.VOYAGER;
    }

    @Override
    public boolean enabled() {
        return settings.enabled() && sessions.hasSessions();
    }

    @Override
    public Profile fetch(String publicIdentifier, Duration budget) {
        Duration remaining = pace.await(type(), budget);
        JsonNode envelope = client.fetchProfileEnvelope(publicIdentifier, remaining);

        UrnGraph graph = UrnGraph.of(envelope);
        JsonNode resolved = graph.rootProfile().orElseThrow(() -> new SourceUnavailableException(
                type(),
                "Voyager answered but contained no member profile entity — the profile may be "
                        + "private, or the decorationId may be stale. Indexed " + graph.size()
                        + " entities."));

        Profile profile = mapper.map(resolved, publicIdentifier, urls.canonicalUrl(publicIdentifier));
        log.debug("Mapped {} — {} roles, {} education, {} skills", publicIdentifier,
                profile.experience().size(), profile.education().size(), profile.skills().size());
        return profile;
    }
}
