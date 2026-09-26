package de.tehwolf.yaft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A boolean-shape provider: {@code {"myToggle": true}}.
 *
 * <p>Maps straight onto {@link #isEnabled(String)} with no time logic, by
 * design (R21). A missing key, a {@code null} and anything that is not a
 * boolean are off.
 *
 * <p>Thread-safe: {@link #replace(Map)} swaps the data atomically.
 */
public final class LocalBooleanProvider implements FeatureProvider {

    private volatile Map<String, Boolean> data;

    /**
     * Answers from {@code data}.
     *
     * @param data toggles by key; {@code null} values are allowed and off
     */
    public LocalBooleanProvider(Map<String, Boolean> data) {
        replace(data);
    }

    /**
     * Builds a provider from a parsed JSON object. Entries whose value is not a
     * boolean are dropped, so a string {@code "true"} cannot switch anything on.
     *
     * @param response the parsed JSON object
     * @return a provider over the boolean entries of {@code response}
     */
    public static LocalBooleanProvider fromResponse(Map<?, ?> response) {
        Map<String, Boolean> data = new LinkedHashMap<>();
        response.forEach((key, value) -> {
            if (key instanceof String k && value instanceof Boolean b) data.put(k, b);
        });
        return new LocalBooleanProvider(data);
    }

    /**
     * Replaces the data, for example after reloading it.
     *
     * @param data toggles by key; {@code null} values are allowed and off
     */
    public void replace(Map<String, Boolean> data) {
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
    }

    /**
     * The current data.
     *
     * @return an unmodifiable view of the toggles by key
     */
    public Map<String, Boolean> data() {
        return data;
    }

    @Override
    public boolean isEnabled(String key) {
        return Boolean.TRUE.equals(data.get(key));
    }
}
