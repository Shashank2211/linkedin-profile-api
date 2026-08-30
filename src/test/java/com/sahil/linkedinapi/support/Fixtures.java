package com.sahil.linkedinapi.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;

/**
 * Loads recorded payloads from {@code src/test/resources/fixtures}.
 *
 * <p>Fixtures are what let this repo's test suite run with no LinkedIn credentials and no
 * network. Clone it, run {@code mvn test}, and every line of parsing and mapping logic is
 * exercised — which matters because the cookie that works today may be dead by the time
 * anyone reviews the code.
 */
public final class Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static JsonNode load(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Fixture not found on the classpath: " + name);
            }
            return MAPPER.readTree(in);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    public static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Bad inline JSON in a test", e);
        }
    }
}
