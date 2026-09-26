package de.tehwolf.yaft.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                    LocalBooleanProvider provider = LocalBooleanProvider.fromResponse((Map<?, ?>) response);
                    assertEquals(expected, provider.data());
                    // The probes include keys absent from the data, which a
                    // comparison of the data alone cannot check (R21).
                    Map<?, ?> probes = (Map<?, ?>) c.get("isEnabled");
                    probes.forEach((key, value) -> assertEquals(value, provider.isEnabled((String) key), (String) key));
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
