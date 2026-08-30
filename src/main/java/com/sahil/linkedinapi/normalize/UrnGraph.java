package com.sahil.linkedinapi.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a REST-li <em>normalized</em> response back into a nested document.
 *
 * <p>Voyager answers with {@code application/vnd.linkedin.normalized+json+2.1}, which is
 * not a document at all. Every entity is flattened into one {@code included[]} array keyed
 * by {@code entityUrn}, and anything that would have been a nested object is replaced by a
 * URN <em>pointer</em> on a key prefixed with {@code *}:
 *
 * <pre>
 * { "data":     { "*elements": ["urn:li:fsd_profile:ACoAAB…"] },
 *   "included": [ { "entityUrn": "urn:li:fsd_profile:ACoAAB…",
 *                   "firstName": "…",
 *                   "*profilePositionGroups": ["urn:li:fsd_profilePositionGroup:(…)"] },
 *                 { "entityUrn": "urn:li:fsd_profilePositionGroup:(…)", … } ] }
 * </pre>
 *
 * <p>Your positions are not inside your profile; they are three hops away. This class does
 * the two generic steps — index, then resolve — so that every mapper downstream can read an
 * ordinary tree and stay readable.
 *
 * <p>Three decisions worth knowing about:
 * <ul>
 *   <li><strong>Cycles are real.</strong> A company points at a position that points back at
 *       the company. The visited set is not defensive programming; without it this
 *       stack-overflows on the first profile you try.</li>
 *   <li><strong>An unresolvable pointer degrades to its URN string</strong> rather than
 *       disappearing. A company we could not expand is still a company we can name by id,
 *       and a mapper can decide what to do with that.</li>
 *   <li><strong>Depth is capped.</strong> Deeply nested entity graphs are not worth the
 *       traversal, and an unbounded walk over an untrusted payload is a denial-of-service
 *       waiting to happen.</li>
 * </ul>
 */
public final class UrnGraph {

    private static final int MAX_DEPTH = 12;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Map<String, JsonNode> byUrn;
    private final JsonNode envelope;

    private UrnGraph(JsonNode envelope, Map<String, JsonNode> byUrn) {
        this.envelope = envelope;
        this.byUrn = byUrn;
    }

    /** Index every entity in {@code included[]} by its {@code entityUrn}. */
    public static UrnGraph of(JsonNode envelope) {
        Map<String, JsonNode> index = new HashMap<>();
        if (envelope != null) {
            JsonNode included = envelope.path("included");
            if (included.isArray()) {
                for (JsonNode node : included) {
                    String urn = node.path("entityUrn").asText(null);
                    if (urn != null && !urn.isBlank()) {
                        index.put(urn, node);
                    }
                }
            }
        }
        return new UrnGraph(envelope == null ? MissingNode.getInstance() : envelope, index);
    }

    public int size() {
        return byUrn.size();
    }

    public Optional<JsonNode> raw(String urn) {
        return Optional.ofNullable(byUrn.get(urn));
    }

    /**
     * The fully-resolved member profile.
     *
     * <p>Tries the documented route first ({@code data."*elements"[0]} as a pointer), then
     * a couple of shapes older decorations use, then falls back to scanning {@code included}
     * for the one entity that is unmistakably a member profile. The fallback exists because
     * the envelope shape moves between decoration versions far more often than the entity
     * shape does.
     */
    public Optional<JsonNode> rootProfile() {
        String urn = firstElementUrn();
        if (urn != null && byUrn.containsKey(urn)) {
            return Optional.of(resolveFrom(urn, byUrn.get(urn)));
        }
        for (JsonNode candidate : byUrn.values()) {
            String entityUrn = candidate.path("entityUrn").asText("");
            if (entityUrn.contains("fsd_profile:") && candidate.has("firstName")) {
                return Optional.of(resolveFrom(entityUrn, candidate));
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves an entity with its own URN already on the visited stack.
     *
     * <p>Without this seeding, the member profile gets expanded a second time the moment
     * something points back at it — a company's employee list, for instance — which is
     * correct but does a great deal of pointless work on every request.
     */
    private JsonNode resolveFrom(String urn, JsonNode node) {
        Deque<String> visiting = new ArrayDeque<>();
        visiting.push(urn);
        return resolve(node, visiting, 0);
    }

    private String firstElementUrn() {
        JsonNode data = envelope.path("data");

        String fromData = elementPointer(data);
        if (fromData != null) {
            return fromData;
        }

        // GraphQL envelope. /voyager/api/graphql wraps the collection one level deeper and
        // keys it by query name, so the pointer sits at
        //   data.data.identityDashProfilesByMemberIdentity."*elements"
        // rather than at data."*elements". The query name is not fixed — it varies with the
        // query the web app happens to run — so we look under every object child rather than
        // hardcoding one, which would break on the next query LinkedIn switches to.
        JsonNode inner = data.path("data");
        if (inner.isObject()) {
            var children = inner.elements();
            while (children.hasNext()) {
                String found = elementPointer(children.next());
                if (found != null) {
                    return found;
                }
            }
        }

        // Some decorations return the entity pointer directly on data.
        JsonNode direct = data.path("*profile");
        return direct.isTextual() ? direct.asText() : null;
    }

    /** First URN in a {@code *elements}/{@code elements} collection on this node, or null. */
    private String elementPointer(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : new String[]{"*elements", "elements"}) {
            JsonNode elements = node.path(key);
            if (elements.isArray() && !elements.isEmpty() && elements.get(0).isTextual()) {
                return elements.get(0).asText();
            }
        }
        return null;
    }

    /** Inline every {@code *}-prefixed reference in {@code node}, recursively. */
    public JsonNode resolve(JsonNode node) {
        return resolve(node, new ArrayDeque<>(), 0);
    }

    private JsonNode resolve(JsonNode node, Deque<String> visiting, int depth) {
        if (node == null || node.isMissingNode()) {
            return MissingNode.getInstance();
        }
        if (depth >= MAX_DEPTH || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode child : node) {
                out.add(resolve(child, visiting, depth + 1));
            }
            return out;
        }

        ObjectNode out = NODES.objectNode();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            JsonNode value = field.getValue();
            if (name.length() > 1 && name.charAt(0) == '*') {
                // Drop the marker: callers read "profilePositionGroups", not "*profilePositionGroups".
                out.set(name.substring(1), follow(value, visiting, depth));
            } else {
                out.set(name, resolve(value, visiting, depth + 1));
            }
        }
        return out;
    }

    private JsonNode follow(JsonNode reference, Deque<String> visiting, int depth) {
        if (reference.isTextual()) {
            return dereference(reference.asText(), visiting, depth);
        }
        if (reference.isArray()) {
            ArrayNode out = NODES.arrayNode();
            for (JsonNode item : reference) {
                out.add(item.isTextual()
                        ? dereference(item.asText(), visiting, depth)
                        : resolve(item, visiting, depth + 1));
            }
            return out;
        }
        return resolve(reference, visiting, depth + 1);
    }

    private JsonNode dereference(String urn, Deque<String> visiting, int depth) {
        JsonNode target = byUrn.get(urn);
        // Unknown or already-being-visited: keep the URN. It is still an identifier.
        if (target == null || visiting.contains(urn)) {
            return TextNode.valueOf(urn);
        }
        visiting.push(urn);
        try {
            return resolve(target, visiting, depth + 1);
        } finally {
            visiting.pop();
        }
    }
}
