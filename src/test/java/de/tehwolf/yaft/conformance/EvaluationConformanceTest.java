package de.tehwolf.yaft.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.tehwolf.yaft.Evaluation;
import de.tehwolf.yaft.Feature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared evaluation cases against this port.
 *
 * <p>These are the cases every YaFT implementation has to pass, so a
 * disagreement here is a disagreement with the spec. Each case brings its own
 * {@code now}, which is why the clock is injectable in the first place.
 */
class EvaluationConformanceTest {

    @TestFactory
    Stream<DynamicTest> evaluation() {
        return Cases.load("evaluation").stream().map(c -> DynamicTest.dynamicTest(Cases.title(c), () -> {
            // Parsed with java.time rather than the code under test, so a
            // parser bug cannot move the case's own clock.
            Instant now = OffsetDateTime.parse((String) c.get("now")).toInstant();

            Map<?, ?> features = (Map<?, ?>) c.get("features");
            Feature feature = features.get(c.get("key")) instanceof Map<?, ?> raw ? feature(raw) : null;

            assertEquals(c.get("expected"), Evaluation.evaluate(feature, now));
        }));
    }

    /**
     * Takes the fields as they are, without normalising: an absent or
     * non-string field becomes {@code null}, so a {@code "value": true} would
     * stay off exactly as R1 demands.
     */
    private static Feature feature(Map<?, ?> raw) {
        return new Feature(
                text(raw.get("key")), text(raw.get("value")), text(raw.get("activeAt")), text(raw.get("disabledAt")), null);
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }
}
