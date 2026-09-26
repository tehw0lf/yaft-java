package de.tehwolf.yaft;

/**
 * Supplies feature data and answers whether a feature is on.
 *
 * <p>Two shapes exist. A <em>feature-shape</em> provider holds full {@link
 * Feature} records and delegates to {@link Evaluation#evaluate} (R20), like
 * {@link LocalFeatureProvider}. A <em>boolean-shape</em> provider holds plain
 * booleans with no time logic (R21), like {@link LocalBooleanProvider}.
 */
@FunctionalInterface
public interface FeatureProvider {

    /**
     * Answers whether a feature is on right now.
     *
     * @param key the feature's key
     * @return {@code true} if the feature is on; {@code false} for a missing key
     */
    boolean isEnabled(String key);
}
