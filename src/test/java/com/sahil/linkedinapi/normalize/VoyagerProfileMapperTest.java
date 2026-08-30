package com.sahil.linkedinapi.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahil.linkedinapi.domain.Experience;
import com.sahil.linkedinapi.domain.Profile;
import com.sahil.linkedinapi.support.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoyagerProfileMapperTest {

    private final VoyagerProfileMapper mapper = new VoyagerProfileMapper();
    private Profile profile;

    @BeforeEach
    void mapFixture() {
        JsonNode resolved = UrnGraph.of(Fixtures.load("voyager-profile.json"))
                .rootProfile().orElseThrow();
        profile = mapper.map(resolved, "ada-lovelace-test",
                "https://www.linkedin.com/in/ada-lovelace-test");
    }

    @Test
    @DisplayName("maps the top-level identity fields")
    void mapsIdentity() {
        assertThat(profile.publicIdentifier()).isEqualTo("ada-lovelace-test");
        assertThat(profile.profileUrl()).isEqualTo("https://www.linkedin.com/in/ada-lovelace-test");
        assertThat(profile.urn()).isEqualTo("urn:li:fsd_profile:ACoAAA_TEST_1");
        assertThat(profile.name().first()).isEqualTo("Ada");
        assertThat(profile.name().last()).isEqualTo("Lovelace");
        assertThat(profile.name().full()).isEqualTo("Ada Lovelace");
        assertThat(profile.headline()).isEqualTo("Mathematician - Analytical Engine");
        assertThat(profile.about()).isEqualTo("Working on notes for the Analytical Engine.");
        assertThat(profile.industry()).isEqualTo("Research");
    }

    @Test
    @DisplayName("reads location from the geo block without inventing a city")
    void mapsLocation() {
        assertThat(profile.location().raw()).isEqualTo("London, England, United Kingdom");
        assertThat(profile.location().country()).isEqualTo("gb");
        // Not derivable from the payload, so it stays null rather than being guessed
        // by splitting the raw string on a comma.
        assertThat(profile.location().city()).isNull();
    }

    @Test
    @DisplayName("builds every image size and picks the largest as the default url")
    void mapsProfilePicture() {
        assertThat(profile.profilePicture()).isNotNull();
        assertThat(profile.profilePicture().sizes()).hasSize(3);
        assertThat(profile.profilePicture().url())
                .isEqualTo("https://media.example.com/img/400_400/ada.jpg");
        assertThat(profile.profilePicture().sizes().get(0).width()).isEqualTo(400);
        assertThat(profile.backgroundImage()).isNull();
    }

    @Test
    @DisplayName("flattens the position group so promotions are not lost")
    void flattensPositionGroups() {
        // The fixture has ONE company with TWO roles. A mapper that reads only the group
        // level returns one entry here — that is the bug this test exists to catch.
        assertThat(profile.experience()).hasSize(2);

        Experience current = profile.experience().get(0);
        assertThat(current.title()).isEqualTo("Principal Mathematician");
        assertThat(current.company().name()).isEqualTo("Analytical Engine Co");
        assertThat(current.company().urn()).isEqualTo("urn:li:fsd_company:9001");
        assertThat(current.company().logo())
                .isEqualTo("https://media.example.com/logo/200_200/aec.png");
        assertThat(current.startDate()).isEqualTo("1843-07");
        assertThat(current.endDate()).isNull();
        assertThat(current.current()).isTrue();
        assertThat(current.employmentType()).isEqualTo("FULL_TIME");
        assertThat(current.locationType()).isEqualTo("ON_SITE");
        assertThat(current.location()).isEqualTo("London, United Kingdom");

        Experience previous = profile.experience().get(1);
        assertThat(previous.title()).isEqualTo("Mathematician");
        assertThat(previous.startDate()).isEqualTo("1840-01");
        assertThat(previous.endDate()).isEqualTo("1843-06");
        assertThat(previous.current()).isFalse();
        assertThat(previous.locationType()).isNull();
    }

    @Test
    @DisplayName("emits ISO-8601 partials, never a padded day")
    void emitsPartialDates() {
        assertThat(profile.education()).hasSize(1);
        assertThat(profile.education().get(0).startDate()).isEqualTo("1832");
        assertThat(profile.education().get(0).endDate()).isEqualTo("1836");
        assertThat(profile.certifications().get(0).issuedDate()).isEqualTo("1843-02");
    }

    @Test
    @DisplayName("maps education, skills, certifications and languages")
    void mapsRemainingSections() {
        var education = profile.education().get(0);
        assertThat(education.school()).isEqualTo("Home Tuition");
        assertThat(education.degree()).isEqualTo("Mathematics");
        assertThat(education.fieldOfStudy()).isEqualTo("Mathematics and Logic");
        assertThat(education.grade()).isEqualTo("Distinction");

        assertThat(profile.skills()).hasSize(2);
        assertThat(profile.skills().get(0).name()).isEqualTo("Algorithms");
        assertThat(profile.skills().get(0).endorsements()).isEqualTo(42);
        // No count in the payload => null, not 0. "Nobody endorsed this" and "we could not
        // see the count" are different facts.
        assertThat(profile.skills().get(1).endorsements()).isNull();

        var certification = profile.certifications().get(0);
        assertThat(certification.name()).isEqualTo("Certificate in Analytical Mechanics");
        assertThat(certification.authority()).isEqualTo("Royal Society");
        assertThat(certification.credentialId()).isEqualTo("RS-1843-07");
        assertThat(certification.expirationDate()).isNull();

        assertThat(profile.languages()).hasSize(1);
        assertThat(profile.languages().get(0).name()).isEqualTo("English");
        assertThat(profile.languages().get(0).proficiency()).isEqualTo("NATIVE_OR_BILINGUAL");
    }

    @Test
    @DisplayName("maps badges and counts, leaving unknown counts null")
    void mapsFlagsAndCounts() {
        assertThat(profile.flags().openToWork()).isTrue();
        assertThat(profile.flags().premium()).isFalse();
        assertThat(profile.counts().followers()).isEqualTo(1815);
        assertThat(profile.counts().connections()).isNull();
    }

    @Test
    @DisplayName("returns a partial profile instead of throwing on a near-empty payload")
    void toleratesMissingSections() {
        JsonNode bare = Fixtures.parse("{\"firstName\":\"Grace\",\"lastName\":\"Hopper\"}");

        Profile result = mapper.map(bare, "grace", "https://www.linkedin.com/in/grace");

        assertThat(result.name().full()).isEqualTo("Grace Hopper");
        assertThat(result.headline()).isNull();
        assertThat(result.experience()).isEmpty();
        assertThat(result.skills()).isEmpty();
        assertThat(result.location()).isEqualTo(Profile.Location.EMPTY);
    }
}
