package com.sahil.linkedinapi.support;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LinkedIn sends dates as {@code {"year": 2024, "month": 6}} — almost never with a day.
 *
 * <p>We emit ISO-8601 <em>partials</em> rather than padding to the first of the month.
 * A client that receives {@code "2024-06"} knows the precision it is getting;
 * a client that receives {@code "2024-06-01"} does not, and will happily compute a
 * tenure figure that is wrong by up to a month.
 */
public final class Dates {

    private Dates() {
    }

    /** @return {@code "2024"}, {@code "2024-06"}, {@code "2024-06-15"} or null. */
    public static String isoPartial(JsonNode dateNode) {
        if (dateNode == null || !dateNode.isObject()) {
            return null;
        }
        int year = dateNode.path("year").asInt(0);
        if (year <= 0) {
            return null;
        }
        int month = dateNode.path("month").asInt(0);
        if (month <= 0 || month > 12) {
            return String.valueOf(year);
        }
        int day = dateNode.path("day").asInt(0);
        if (day <= 0 || day > 31) {
            return "%04d-%02d".formatted(year, month);
        }
        return "%04d-%02d-%02d".formatted(year, month, day);
    }

    /** Start of a {@code dateRange} object. */
    public static String start(JsonNode dateRange) {
        return isoPartial(Json.at(dateRange, "start"));
    }

    /** End of a {@code dateRange} object. Null end means the entry is current. */
    public static String end(JsonNode dateRange) {
        return isoPartial(Json.at(dateRange, "end"));
    }
}
