package de.tehwolf.yaft;

import java.time.InstantSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A feature-shape provider over data held in memory.
 *
 * <p>The data can come from anywhere: built by hand, or parsed from a JSON
 * file or a backend response with any JSON library and passed to {@link
 * #fromResponse(Object)}. Time bounds are evaluated against the injected
 * clock, which defaults to the system time (R9).
 *
 * <p>Thread-safe: {@link #replace(Map)} swaps the data atomically.
 */
public final class LocalFeatureProvider implements FeatureProvider {

    private final InstantSource clock;
    private volatile Map<String, Feature> data;

    /**
     * Evaluates {@code data} against the system clock.
     *
     * @param data features by key; {@code null} values are allowed and off
     */
    public LocalFeatureProvider(Map<String, Feature> data) {
        this(data, InstantSource.system());
    }

    /**
     * Evaluates {@code data} against {@code clock}.
     *
     * @param data features by key; {@code null} values are allowed and off
     * @param clock the source of "now"
     */
    public LocalFeatureProvider(Map<String, Feature> data, InstantSource clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        replace(data);
    }

    /**
     * Builds a provider from a parsed JSON response, in any of the backend's
     * envelopes and field spellings.
     *
     * @param response the parsed JSON, as {@code Map}/{@code List}/{@code String}
     * @return a provider evaluating against the system clock
     * @see Mapping#normaliseCollection(Object)
     */
    public static LocalFeatureProvider fromResponse(Object response) {
        return new LocalFeatureProvider(Mapping.normaliseCollection(response));
    }

    /**
     * Like {@link #fromResponse(Object)}, evaluating against {@code clock}.
     *
     * @param response the parsed JSON, as {@code Map}/{@code List}/{@code String}
     * @param clock the source of "now"
     * @return a provider evaluating against {@code clock}
     */
    public static LocalFeatureProvider fromResponse(Object response, InstantSource clock) {
        return new LocalFeatureProvider(Mapping.normaliseCollection(response), clock);
    }

    /**
     * Replaces the data, for example after reloading it.
     *
     * @param data features by key; {@code null} values are allowed and off
     */
    public void replace(Map<String, Feature> data) {
        // Copied into a LinkedHashMap rather than Map.copyOf, which rejects
        // null values and would throw on a get(null).
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
    }

    /**
     * The current data.
     *
     * @return an unmodifiable view of the features by key
     */
    public Map<String, Feature> data() {
        return data;
    }

    @Override
    public boolean isEnabled(String key) {
        return Evaluation.evaluate(data.get(key), clock.instant());
    }
}
