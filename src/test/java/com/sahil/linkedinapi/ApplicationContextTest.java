package com.sahil.linkedinapi;

import com.sahil.linkedinapi.acquisition.ProfileSourceChain;
import com.sahil.linkedinapi.api.ProfileController;
import com.sahil.linkedinapi.session.SessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application with <strong>no LinkedIn credentials at all</strong>.
 *
 * <p>That is the point. The service has to start, wire itself and stay healthy when the
 * cookies are missing — otherwise a reviewer who clones the repo and runs it sees a stack
 * trace instead of an API, and every deployment becomes hostage to a cookie being alive at
 * boot time.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "linkedin-api.session.li-at=",
        "linkedin-api.session.jsessionid=",
        "linkedin-api.api-keys="
})
class ApplicationContextTest {

    @Autowired
    private ProfileController controller;

    @Autowired
    private SessionManager sessions;

    @Autowired
    private ProfileSourceChain chain;

    @Test
    @DisplayName("starts cleanly with no credentials configured")
    void contextLoadsWithoutCredentials() {
        assertThat(controller).isNotNull();
        assertThat(sessions.hasSessions()).isFalse();
        // Voyager is off without a session; the public-HTML fallback is still registered.
        assertThat(chain.breakerHealth()).containsKey(
                com.sahil.linkedinapi.acquisition.SourceType.PUBLIC_HTML);
    }
}
