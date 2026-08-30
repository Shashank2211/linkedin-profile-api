package com.sahil.linkedinapi.domain;

import java.util.List;

/**
 * A picture, in every size LinkedIn offers.
 *
 * <p>LinkedIn stores images as a {@code rootUrl} plus a list of artifacts, each with its
 * own path segment and dimensions; the usable URL is the concatenation. We expose all of
 * them and set {@code url} to the largest, so a caller that just wants "the avatar" has
 * one field and a caller that wants a thumbnail does not have to re-fetch.
 *
 * <p><strong>These URLs are signed and expire</strong> — typically within hours. A consumer
 * must fetch promptly or rehost. This is listed in the README's known limitations, and it
 * is the single most common surprise for anyone integrating with LinkedIn image data.
 */
public record ImageAsset(String url, List<Variant> sizes) {

    public record Variant(Integer width, Integer height, String url) {
    }
}
