package de.tehwolf.yaft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a backend response into provider data.
 *
 * <p>This is the single definition of YaFT's mapping rules (SPEC R22-R25, R29), the
 * way {@link Evaluation} is the single definition of the evaluation rules. It
 * works on the plain {@code Map}/{@code List}/{@code String} tree every JSON
 * library can produce, so the core does not depend on one.
 */
public final class Mapping {

    private Mapping() {}

    /**
     * Normalises one entry into a {@link Feature}, whichever spelling it
     * arrived in.
     *
     * <p>Fields are read by <em>presence</em>, not by content (R23): a present
     * but empty {@code "value": ""} wins over a capitalised {@code "Value"},
     * because falling through to the other spelling would turn an off feature
     * on. Backends from 0.2.0 on only send the lowercase spelling; the
     * capitalised one is read because older instances are still around (R22a).
     *
     * @param raw one entry of a parsed JSON response
     * @return the normalised feature; unset dates become {@code ""}
     */
    public static Feature normaliseFeature(Map<?, ?> raw) {
        Object tags = field(raw, "tags", "Tags");

        return new Feature(
                text(field(raw, "key", "Key")),
                text(field(raw, "value", "Value")),
                date(field(raw, "activeAt", "ActiveAt")),
                date(field(raw, "disabledAt", "DisabledAt")),
                // Filtered rather than cast: a backend sending a mixed array
                // would otherwise hand callers a non-string through a
                // List<String>.
                tags instanceof List<?> list
                        ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
                        : List.of());
    }

    /**
     * Normalises a whole response into features keyed by their key.
     *
     * <p>Three envelopes are accepted, because the backend uses all three
     * (R22): {@code {"toggles": [...]}} for a UUID group, {@code {"value":
     * [...]}} for the same thing under another name, and a flat object for a
     * single toggle. An entry without a usable key is skipped rather than
     * stored under {@code ""}, where {@code isEnabled("")} could reach it
     * (R25).
     *
     * @param response a parsed JSON response
     * @return the features in response order; empty for anything unreadable
     */
    public static Map<String, Feature> normaliseCollection(Object response) {
        if (!(response instanceof Map<?, ?> body)) return Map.of();

        List<?> entries;
        if (body.get("toggles") instanceof List<?> toggles) {
            entries = toggles;
        } else if (body.get("value") instanceof List<?> value) {
            entries = value;
        } else {
            entries = List.of(body);
        }

        Map<String, Feature> data = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) continue;

            Feature feature = normaliseFeature(raw);
            if (!feature.key().isEmpty()) data.put(feature.key(), feature);
        }
        return Collections.unmodifiableMap(data);
    }

    /**
     * Normalises a boolean-shape payload, {@code {"myToggle": true}}.
     *
     * <p>Only real booleans are kept (R29). Anything else -- {@code "true"},
     * {@code "false"}, {@code 1}, {@code null} -- is dropped, so its key reads
     * as missing and therefore off.
     *
     * @param response a parsed JSON object
     * @return the boolean entries in response order; empty for anything unreadable
     */
    public static Map<String, Boolean> normaliseBooleans(Object response) {
        if (!(response instanceof Map<?, ?> body)) return Map.of();

        Map<String, Boolean> data = new LinkedHashMap<>();
        body.forEach((key, value) -> {
            if (key instanceof String k && value instanceof Boolean b) data.put(k, b);
        });
        return Collections.unmodifiableMap(data);
    }

    private static Object field(Map<?, ?> raw, String lower, String upper) {
        if (raw.containsKey(lower)) return raw.get(lower);
        if (raw.containsKey(upper)) return raw.get(upper);
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The backend sends {@code null} for an unset bound, fixtures use {@code ""}; both mean none (R24). */
    private static String date(Object value) {
        return value instanceof String s ? s : "";
    }
}
