package de.tehwolf.yaft.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import de.tehwolf.yaft.ApiFeatureProvider;
import de.tehwolf.yaft.Feature;
import de.tehwolf.yaft.LocalBooleanProvider;
import de.tehwolf.yaft.Mapping;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the shared mapping cases against this port: which envelopes exist, how
 * the two field spellings are reconciled, and that a present but empty value
 * is kept rather than replaced.
 */
class MappingConformanceTest {

    private static final String GROUP = "896ea308-382f-46b0-bc59-d93a28013633";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestFactory
    Stream<DynamicTest> mapping() {
        return Cases.load("mapping").stream().map(c -> DynamicTest.dynamicTest(Cases.title(c), () -> {
            Object response = c.get("response");
            Map<?, ?> expected = (Map<?, ?>) c.get("expected");

            switch ((String) c.get("shape")) {
                case "feature" -> {
                    if (!c.containsKey("held")) {
                        assertEquals(expected, fields(Mapping.normaliseCollection(response)));
                    } else if (c.get("held") instanceof Map<?, ?> held) {
                        Object retry = c.get("retry");
                        if (retry != null && !(retry instanceof Map)) {
                            throw Cases.unsupported("retry", retry, c.get("name"));
                        }
                        refreshOver(held, response, expected, (Map<?, ?>) retry);
                    } else {
                        // A held that is not a map must not fall through to a
                        // plain mapping: that would test a different rule.
                        throw Cases.unsupported("held", c.get("held"), c.get("name"));
                    }
                }
                case "boolean" -> {
                    LocalBooleanProvider provider = LocalBooleanProvider.fromResponse(response);
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

    /**
     * Runs a refresh case (R30) through the real API provider against a stub
     * backend: {@code held} is served and loaded first, then {@code response}
     * under a new hash. A body that is not a group fails the second refresh;
     * that is expected, and the data it leaves behind is what the case asserts.
     * A {@code retry} is served under the same hash: a port that recorded it
     * on the rejected body would never fetch again.
     */
    private static void refreshOver(Map<?, ?> held, Object response, Map<?, ?> expected, Map<?, ?> retry)
            throws Exception {
        AtomicReference<String> hash = new AtomicReference<>("held");
        AtomicReference<Object> features = new AtomicReference<>(Map.of("toggles", held.values()));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Object body = exchange.getRequestURI().getPath().startsWith("/collectionHash/")
                    ? Map.of("collectionHash", hash.get())
                    : features.get();
            byte[] bytes = JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try (ApiFeatureProvider provider = ApiFeatureProvider.builder(base, GROUP, body -> JSON.readValue(body, Object.class))
                .build()) {
            assertEquals(true, provider.refresh(), "loading held");
            hash.set("response");
            features.set(response);
            provider.refreshQuietly();
            assertEquals(expected, fields(provider.data()), "after the refresh");
            if (retry != null) {
                features.set(retry.get("response"));
                provider.refreshQuietly();
                assertEquals(retry.get("expected"), fields(provider.data()), "after the retry");
            }
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, Object> fields(Map<String, Feature> data) {
        Map<String, Object> actual = new LinkedHashMap<>();
        data.forEach((key, feature) -> actual.put(key, fields(feature)));
        return actual;
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
