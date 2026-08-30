package com.sahil.linkedinapi.acquisition;

import com.sahil.linkedinapi.domain.Profile;

import java.time.Duration;

/**
 * One way of getting a profile.
 *
 * <p>The whole design rests on this interface staying small. Acquisition is the part
 * that breaks — cookies expire, decoration ids move, markup changes — and normalization
 * is the part that stays true. Keeping them behind one method means a new source is a
 * new class, not a change to the service.
 */
public interface ProfileSource {

    SourceType type();

    /** False when the source is switched off in config or missing what it needs to run. */
    boolean enabled();

    /**
     * @param publicIdentifier a slug already validated by {@code ProfileUrlParser} —
     *                         implementations must compose their own URL from it and a
     *                         hardcoded host, never from caller input.
     * @param budget           the remaining wall-clock allowance for this request.
     * @throws ProfileNotFoundException      the slug resolves to nothing (authoritative — stops the chain)
     * @throws ProfileNotAccessibleException the profile exists but is walled off
     * @throws SourceUnavailableException    this source failed; the chain should try the next one
     */
    Profile fetch(String publicIdentifier, Duration budget);
}
