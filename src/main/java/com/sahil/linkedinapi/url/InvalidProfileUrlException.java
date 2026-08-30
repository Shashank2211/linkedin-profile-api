package com.sahil.linkedinapi.url;

import com.sahil.linkedinapi.api.error.ApiException;
import com.sahil.linkedinapi.api.error.ErrorCode;

public class InvalidProfileUrlException extends ApiException {

    public InvalidProfileUrlException(String message) {
        super(ErrorCode.INVALID_PROFILE_URL, message);
    }
}
