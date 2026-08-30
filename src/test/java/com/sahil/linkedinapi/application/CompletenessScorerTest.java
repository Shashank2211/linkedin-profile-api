package com.sahil.linkedinapi.application;

import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.normalize.UrnGraph;
import com.sahil.linkedinapi.normalize.VoyagerProfileMapper;
import com.sahil.linkedinapi.support.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CompletenessScorerTest {

    private final CompletenessScorer scorer = new CompletenessScorer();

    @Test
    @DisplayName("a fully-populated Voyager profile scores 1.0")
    void fullProfileScoresOne() {
        Profile profile = new VoyagerProfileMapper().map(
                UrnGraph.of(Fixtures.load("voyager-profile.json")).rootProfile().orElseThrow(),
                "ada-lovelace-test", "https://www.linkedin.com/in/ada-lovelace-test");

        assertThat(scorer.score(profile)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an empty profile scores 0.0 rather than throwing")
    void emptyProfileScoresZero() {
        Profile empty = new Profile(null, null, null, new Profile.Name(null, null, null),
                null, null, Profile.Location.EMPTY, null, null, null,
                Profile.Flags.EMPTY, Profile.Counts.EMPTY,
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(scorer.score(empty)).isZero();
        assertThat(scorer.score(null)).isZero();
    }

    @Test
    @DisplayName("a public-page shaped result scores well below a Voyager one")
    void thinProfileScoresLow() {
        // Name + headline + location + one role: what the logged-out page typically gives.
        Profile thin = new Profile("x", "https://www.linkedin.com/in/x", null,
                Profile.Name.of("Grace", "Hopper"), "Rear Admiral", null,
                new Profile.Location("Arlington, Virginia", null, "us"), null, null, null,
                Profile.Flags.EMPTY, Profile.Counts.EMPTY,
                List.of(new com.sahil.linkedinapi.domain.Experience(
                        "Rear Admiral", com.sahil.linkedinapi.domain.Experience.Company.EMPTY,
                        null, null, null, null, null, true, null, List.of())),
                List.of(), List.of(), List.of(), List.of());

        // name .15 + headline .15 + location .10 + experience .20
        assertThat(scorer.score(thin)).isCloseTo(0.60, within(0.001));
    }
}
