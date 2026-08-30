package com.sahil.linkedinapi.acquisition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SourceBreakerTest {

    @Test
    @DisplayName("stays closed below the failure threshold")
    void staysClosedBelowThreshold() {
        var breaker = new SourceBreaker(3, Duration.ofMinutes(2));

        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    @DisplayName("opens on the threshold failure")
    void opensOnThreshold() {
        var breaker = new SourceBreaker(3, Duration.ofMinutes(2));

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowsRequest()).isFalse();
    }

    @Test
    @DisplayName("a success resets the run of failures")
    void successResetsCounter() {
        var breaker = new SourceBreaker(2, Duration.ofMinutes(2));

        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();

        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    @DisplayName("closes again once the open window elapses")
    void closesAfterWindow() throws InterruptedException {
        var breaker = new SourceBreaker(1, Duration.ofMillis(60));

        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();

        Thread.sleep(120);

        assertThat(breaker.allowsRequest()).isTrue();
    }

    @Test
    @DisplayName("after the window, one more failure re-opens it")
    void reopensAfterTrialFailure() throws InterruptedException {
        var breaker = new SourceBreaker(1, Duration.ofMillis(60));

        breaker.recordFailure();
        Thread.sleep(120);
        assertThat(breaker.allowsRequest()).isTrue();

        breaker.recordFailure();

        assertThat(breaker.isOpen()).isTrue();
    }
}
