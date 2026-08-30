package com.sahil.linkedinapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Null-safe navigation helpers for the Voyager payload.
 *
 * <p>Every accessor takes a <em>list</em> of candidate dotted paths and returns the first
 * one that yields a usable value. That is deliberate: LinkedIn moves fields between
 * shapes across decoration versions (a headline has lived at {@code headline},
 * {@code profile.headline} and inside a localized map), and a mapper that tries three
 * known locations degrades gracefully where one that hardcodes a single path returns null.
 */
public final class Json {

    private Json() {
    }

    /** Walk a dotted path. Never returns null; returns a missing node instead. */
    public static JsonNode at(JsonNode root, String dotted) {
        if (root == null || dotted == null || dotted.isBlank()) {
            return MissingNode.getInstance();
        }
        JsonNode cur = root;
        for (String segment : dotted.split("\\.")) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) {
                return MissingNode.getInstance();
            }
            cur = cur.path(segment);
        }
        return cur == null ? MissingNode.getInstance() : cur;
    }

    /** First non-blank textual value among the candidate paths, else null. */
    public static String text(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = at(root, path);
            String value = readText(node);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Reads a value that may be a plain string or one of LinkedIn's localized shapes:
     * {@code {"text": "..."}} or {@code {"localized": {"en_US": "..."}}}.
     */
    private static String readText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            return blankToNull(node.asText());
        }
        if (node.isObject()) {
            String direct = blankToNull(node.path("text").asText(null));
            if (direct != null) {
                return direct;
            }
            JsonNode localized = node.path("localized");
            if (localized.isObject()) {
                JsonNode preferred = localized.path("en_US");
                if (preferred.isTextual()) {
                    return blankToNull(preferred.asText());
                }
                var names = localized.fieldNames();
                if (names.hasNext()) {
                    return blankToNull(localized.path(names.next()).asText(null));
                }
            }
        }
        return null;
    }

    public static Integer integer(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = at(root, path);
            if (node.isNumber()) {
                return node.asInt();
            }
            if (node.isTextual()) {
                try {
                    return Integer.valueOf(node.asText().replaceAll("[^0-9-]", ""));
                } catch (NumberFormatException ignored) {
                    // fall through to the next candidate path
                }
            }
        }
        return null;
    }

    public static Boolean bool(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode node = at(root, path);
            if (node.isBoolean()) {
                return node.asBoolean();
            }
        }
        return null;
    }

    /**
     * Normalizes the two collection shapes Voyager uses: a bare array, or an object with
     * an {@code elements} array. Returns an empty list for anything else.
     */
    public static List<JsonNode> elements(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        JsonNode array = node.isArray() ? node : node.path("elements");
        if (array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    /** {@link #elements(JsonNode)} applied to the first candidate path that yields items. */
    public static List<JsonNode> elementsAt(JsonNode root, String... paths) {
        for (String path : paths) {
            List<JsonNode> found = elements(at(root, path));
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
