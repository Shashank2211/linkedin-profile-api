package com.sahil.linkedinapi.application;

import com.sahil.linkedinapi.domain.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scores how much of a profile we actually managed to read, 0.0 to 1.0.
 *
 * <p>This exists to answer a question the data itself cannot: is this person's
 * certifications list empty because they have none, or because we could not see that
 * section? A caller comparing {@code certifications: []} across a Voyager result and a
 * public-page result has no way to tell — unless the response also says how much of the
 * profile came through.
 *
 * <p>Weights are deliberately lopsided toward the fields a consumer actually needs. A
 * profile with a name, headline and full work history is far more useful than one with a
 * name and a long list of languages, and the score should say so.
 */
@Component
public class CompletenessScorer {

    private static final double NAME = 0.15;
    private static final double HEADLINE = 0.15;
    private static final double EXPERIENCE = 0.20;
    private static final double ABOUT = 0.10;
    private static final double LOCATION = 0.10;
    private static final double PICTURE = 0.10;
    private static final double EDUCATION = 0.10;
    private static final double SKILLS = 0.05;
    private static final double CERTIFICATIONS = 0.025;
    private static final double LANGUAGES = 0.025;

    public double score(Profile profile) {
        if (profile == null) {
            return 0.0;
        }
        double score = 0.0;
        score += present(profile.name() == null ? null : profile.name().full()) ? NAME : 0;
        score += present(profile.headline()) ? HEADLINE : 0;
        score += present(profile.about()) ? ABOUT : 0;
        score += profile.location() != null && present(profile.location().raw()) ? LOCATION : 0;
        score += profile.profilePicture() != null ? PICTURE : 0;
        score += notEmpty(profile.experience()) ? EXPERIENCE : 0;
        score += notEmpty(profile.education()) ? EDUCATION : 0;
        score += notEmpty(profile.skills()) ? SKILLS : 0;
        score += notEmpty(profile.certifications()) ? CERTIFICATIONS : 0;
        score += notEmpty(profile.languages()) ? LANGUAGES : 0;
        // Two decimal places: the extra precision would imply a confidence we do not have.
        return Math.round(score * 100.0) / 100.0;
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }
}
