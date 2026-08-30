package com.sahil.linkedinapi.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.sahil.linkedinapi.domain.ImageAsset;
import com.sahil.linkedinapi.support.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds usable image URLs out of LinkedIn's {@code VectorImage} shape. */
public final class ImageMapper {

    private ImageMapper() {
    }

    /**
     * @param container the node that holds the picture, e.g. the profile itself
     * @param paths     candidate dotted paths to the picture object, tried in order
     */
    public static ImageAsset from(JsonNode container, String... paths) {
        for (String path : paths) {
            JsonNode vector = findVectorImage(Json.at(container, path));
            if (vector != null) {
                ImageAsset asset = build(vector);
                if (asset != null) {
                    return asset;
                }
            }
        }
        return null;
    }

    /** The vectorImage sits at different depths depending on decoration version. */
    private static JsonNode findVectorImage(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String candidate : new String[]{
                "displayImageReference.vectorImage",
                "vectorImage",
                "displayImage.vectorImage",
                "artifacts"}) {
            JsonNode found = Json.at(node, candidate);
            if (!found.isMissingNode() && !found.isNull()) {
                // "artifacts" means node itself is already the vector image.
                return candidate.equals("artifacts") ? node : found;
            }
        }
        return null;
    }

    private static ImageAsset build(JsonNode vectorImage) {
        String rootUrl = Json.text(vectorImage, "rootUrl");
        JsonNode artifacts = vectorImage.path("artifacts");
        if (rootUrl == null || !artifacts.isArray() || artifacts.isEmpty()) {
            return null;
        }
        List<ImageAsset.Variant> variants = new ArrayList<>();
        for (JsonNode artifact : artifacts) {
            String segment = Json.text(artifact, "fileIdentifyingUrlPathSegment");
            if (segment == null) {
                continue;
            }
            variants.add(new ImageAsset.Variant(
                    Json.integer(artifact, "width"),
                    Json.integer(artifact, "height"),
                    rootUrl + segment));
        }
        if (variants.isEmpty()) {
            return null;
        }
        variants.sort(Comparator.comparing(
                v -> v.width() == null ? 0 : v.width(), Comparator.reverseOrder()));
        return new ImageAsset(variants.get(0).url(), List.copyOf(variants));
    }
}
