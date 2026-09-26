package de.tehwolf.yaft.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.tehwolf.yaft.Feature;
import de.tehwolf.yaft.LocalBooleanProvider;
import de.tehwolf.yaft.Mapping;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs the shared mapping cases against this port: which envelopes exist, how
 * the two field spellings are reconciled, and that a present but empty value
 * is kept rather than replaced.
 */
class MappingConformanceTest {

    /** A key none of the cases uses, to check that a missing key is off (R21). */
    private static final String ABSENT = "yaft-conformance-absent-key";

    @TestFactory
    Stream<DynamicTest> mapping() {
        return Cases.load("mapping").stream().map(c -> DynamicTest.dynamicTest(Cases.title(c), () -> {
            Object response = c.get("response");
            Map<?, ?> expected = (Map<?, ?>) c.get("expected");

            switch ((String) c.get("shape")) {
                case "feature" -> {
                    Map<String, Object> actual = new LinkedHashMap<>();
                    Mapping.normaliseCollection(response).forEach((key, feature) -> actual.put(key, fields(feature)));
                    assertEquals(expected, actual);
                }
                case "boolean" -> {
                    // Checked through the provider rather than by comparing the
                    // response with itself: what matters is what isEnabled says.
                    LocalBooleanProvider provider = LocalBooleanProvider.fromResponse((Map<?, ?>) response);
                    assertEquals(expected, provider.data());
                    expected.forEach((key, value) -> assertEquals(value, provider.isEnabled((String) key), (String) key));
                    assertFalse(provider.isEnabled(ABSENT));
                }
                default -> throw Cases.unsupported("shape", c.get("shape"), c.get("name"));
            }
        }));
    }

    /** The feature in the case files' own spelling. */
    private static Map<String, Object> fields(Feature feature) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("key", feature.key());
        fields.put("value", feature.value());
        fields.put("activeAt", feature.activeAt());
        fields.put("disabledAt", feature.disabledAt());
        fields.put("tags", feature.tags());
        return fields;
    }
}
