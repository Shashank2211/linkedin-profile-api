package com.sahil.linkedinapi.acquisition;

import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;

/**
 * The member exists but we cannot see them — a private profile, or every source hit an
 * auth wall.
 *
 * <p>This is a 422, not a 500. The request was well-formed and we understood it
 * perfectly; the data is simply not visible from where we stand. Reporting it as a
 * server error would tell the caller to retry, which will not help.
 */
public class ProfileNotAccessibleException extends ApiException {

    public ProfileNotAccessibleException(String message) {
        super(ErrorCode.PROFILE_NOT_ACCESSIBLE, message);
    }
}
