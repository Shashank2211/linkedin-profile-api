package com.sahil.linkedinapi.acquisition;

import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;

/**
 * The identifier resolves to nothing. Authoritative — trying another source cannot
 * conjure a member who does not exist, so this stops the chain immediately.
 */
public class ProfileNotFoundException extends ApiException {

    public ProfileNotFoundException(String publicIdentifier) {
        super(ErrorCode.PROFILE_NOT_FOUND,
                "No LinkedIn member found for identifier '" + publicIdentifier + "'.");
    }
}
