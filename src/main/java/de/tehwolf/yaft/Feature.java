package de.tehwolf.yaft;

import java.util.List;

/**
 * A feature toggle as the backend stores it.
 *
 * <p>{@code value} is a string, not a boolean: the backend stores it that way,
 * and only the exact string {@code "true"} switches a feature on (SPEC R1, R4).
 * {@code activeAt} and {@code disabledAt} are optional RFC 3339 bounds; {@code
 * null} and {@code ""} both mean "no bound" (R2).
 *
 * @param key the feature's key, unique within a provider
 * @param value {@code "true"} or {@code "false"}; anything else is off
 * @param activeAt the instant the feature switches on, or {@code null}/{@code ""}
 * @param disabledAt the instant the feature switches off, or {@code null}/{@code ""}
 * @param tags optional labels; {@code null} becomes an empty list
 */
public record Feature(String key, String value, String activeAt, String disabledAt, List<String> tags) {

    /** Copies {@code tags}, so a caller cannot change a feature after the fact. */
    public Feature {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * A feature without time bounds or tags.
     *
     * @param key the feature's key
     * @param value {@code "true"} or {@code "false"}
     */
    public Feature(String key, String value) {
        this(key, value, "", "", List.of());
    }
}
