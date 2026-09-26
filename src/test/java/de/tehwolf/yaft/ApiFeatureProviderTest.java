package de.tehwolf.yaft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Against a real HTTP server, so request building, status handling and limits are exercised for real. */
class ApiFeatureProviderTest {

    private static final String GROUP = "896ea308-382f-46b0-bc59-d93a28013633";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ApiFeatureProvider.JsonDecoder DECODE = body -> JSON.readValue(body, Object.class);

    /** Path -> (status, body). */
    private final Map<String, Object[]> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private HttpServer server;
    private URI base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            Object[] route = routes.getOrDefault(path, new Object[] {404, "{\"error\":\"Feature not found\"}"});
            if (route[1] instanceof Duration delay) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                route = new Object[] {200, "{}"};
            }
            byte[] body = ((String) route[1]).getBytes(StandardCharsets.UTF_8);
            if ((int) route[0] == 302) exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders((int) route[0], body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String hash, String features) {
        routes.put("/collectionHash/" + GROUP, new Object[] {200, "{\"collectionHash\":\"" + hash + "\"}"});
        routes.put("/features/" + GROUP, new Object[] {200, features});
    }

    private ApiFeatureProvider provider() {
        return ApiFeatureProvider.builder(base, GROUP, DECODE).build();
    }

    private int fetches() {
        return hits.getOrDefault("/features/" + GROUP, new AtomicInteger()).get();
    }

    @Test
    void loadsAGroupInEitherSpelling() throws Exception {
        serve("h1", """
                {"toggles": [
                  {"key": "%1$s|new", "value": "true", "activeAt": null, "disabledAt": null, "tags": []},
                  {"Key": "%1$s|old", "Value": "false", "ActiveAt": null, "DisabledAt": null}
                ]}""".formatted(GROUP));
        ApiFeatureProvider provider = provider();

        assertFalse(provider.isEnabled(GROUP + "|new"), "nothing is fetched before the first refresh");
        assertTrue(provider.refresh());
        assertTrue(provider.isEnabled(GROUP + "|new"));
        assertFalse(provider.isEnabled(GROUP + "|old"));
        assertFalse(provider.isEnabled(GROUP + "|missing"));
    }

    @Test
    void refetchesOnlyWhenTheHashChanges() throws Exception {
        serve("h1", "{\"toggles\": []}");
        ApiFeatureProvider provider = provider();

        assertTrue(provider.refresh());
        assertFalse(provider.refresh());
        assertEquals(1, fetches());

        serve("h2", "{\"toggles\": [{\"key\": \"k\", \"value\": \"true\"}]}");
        assertTrue(provider.refresh());
        assertEquals(2, fetches());
        assertTrue(provider.isEnabled("k"));
    }

    @Test
    void evaluatesTimeBoundsLocallyAgainstTheClock() throws Exception {
        serve("h1", """
                {"toggles": [{"key": "k", "value": "true", "activeAt": "2026-09-18T12:00:00Z", "disabledAt": null}]}""");
        Instant[] now = {Instant.parse("2026-09-18T11:59:59Z")};
        ApiFeatureProvider provider =
                ApiFeatureProvider.builder(base, GROUP, DECODE).clock(() -> now[0]).build();
        provider.refresh();

        assertFalse(provider.isEnabled("k"));
        // No refresh in between: the flip does not wait for the backend (R26).
        now[0] = Instant.parse("2026-09-18T12:00:00Z");
        assertTrue(provider.isEnabled("k"));
    }

    @Test
    void keepsThePreviousDataWhenARefreshFails() throws Exception {
        serve("h1", "{\"toggles\": [{\"key\": \"k\", \"value\": \"true\"}]}");
        ApiFeatureProvider provider = provider();
        provider.refresh();

        routes.put("/collectionHash/" + GROUP, new Object[] {500, "{}"});
        IOException error = assertThrows(IOException.class, provider::refresh);
        assertTrue(error.getMessage().contains("500"), error.getMessage());
        assertTrue(provider.isEnabled("k"));
        assertFalse(provider.refreshQuietly());
        assertTrue(provider.isEnabled("k"));
    }

    @Test
    void retriesAfterAFailedFetchEvenIfTheHashIsUnchanged() throws Exception {
        routes.put("/collectionHash/" + GROUP, new Object[] {200, "{\"collectionHash\":\"h1\"}"});
        routes.put("/features/" + GROUP, new Object[] {503, "{}"});
        ApiFeatureProvider provider = provider();
        assertThrows(IOException.class, provider::refresh);

        // The hash was not recorded, so the next refresh fetches again.
        serve("h1", "{\"toggles\": [{\"key\": \"k\", \"value\": \"true\"}]}");
        assertTrue(provider.refresh());
        assertTrue(provider.isEnabled("k"));
    }

    @Test
    void rejectsAnOversizedBody() {
        serve("h1", "{\"toggles\": [" + "{\"key\": \"k\", \"value\": \"true\"},".repeat(100) + "{}]}");
        ApiFeatureProvider provider =
                ApiFeatureProvider.builder(base, GROUP, DECODE).maxBodyBytes(512).build();

        IOException error = assertThrows(IOException.class, provider::refresh);
        assertTrue(error.getMessage().contains("more than 512 bytes"), error.getMessage());
        assertEquals(Map.of(), provider.data());
    }

    @Test
    void wrapsABodyThatDoesNotParse() {
        serve("h1", "not json");
        IOException error = assertThrows(IOException.class, provider()::refresh);
        assertTrue(error.getMessage().contains("does not parse"), error.getMessage());
    }

    @Test
    void requiresAHash() {
        routes.put("/collectionHash/" + GROUP, new Object[] {200, "{\"somethingElse\": 1}"});
        IOException error = assertThrows(IOException.class, provider()::refresh);
        assertTrue(error.getMessage().contains("no collectionHash"), error.getMessage());
    }

    @Test
    void doesNotFollowRedirects() {
        routes.put("/collectionHash/" + GROUP, new Object[] {302, ""});
        routes.put("/elsewhere", new Object[] {200, "{\"collectionHash\":\"h1\"}"});

        assertThrows(IOException.class, provider()::refresh);
        assertEquals(0, hits.getOrDefault("/elsewhere", new AtomicInteger()).get());
    }

    @Test
    void timesOut() {
        routes.put("/collectionHash/" + GROUP, new Object[] {200, Duration.ofSeconds(2)});
        ApiFeatureProvider provider =
                ApiFeatureProvider.builder(base, GROUP, DECODE).timeout(Duration.ofMillis(200)).build();

        assertThrows(IOException.class, provider::refresh);
    }

    @Test
    void acceptsATrailingSlashOnTheBaseUrl() throws Exception {
        serve("h1", "{\"toggles\": [{\"key\": \"k\", \"value\": \"true\"}]}");
        ApiFeatureProvider provider =
                ApiFeatureProvider.builder(URI.create(base + "/"), GROUP, DECODE).build();

        assertTrue(provider.refresh());
        assertEquals(1, fetches());
    }

    @Test
    void rejectsAGroupThatIsNotACanonicalUuid() {
        for (String bad : List.of(
                "../secret", GROUP + "/x", GROUP + "%2F", "1-1-1-1-1", GROUP + " ", "", "not-a-uuid")) {
            assertThrows(
                    IllegalArgumentException.class, () -> ApiFeatureProvider.builder(base, bad, DECODE), bad);
        }
        // Upper case is the same UUID and is normalised.
        ApiFeatureProvider.builder(base, GROUP.toUpperCase(), DECODE).build();
    }

    @Test
    void rejectsUnusableBaseUrls() {
        for (String bad : List.of("file:///etc/passwd", "ftp://host", "localhost:8080", "https://host/?q=1", "https://host/#f")) {
            assertThrows(
                    IllegalArgumentException.class, () -> ApiFeatureProvider.builder(URI.create(bad), GROUP, DECODE), bad);
        }
    }

    @Test
    void worksWithAnyDecoderIncludingAHandWrittenTree() throws Exception {
        routes.put("/collectionHash/" + GROUP, new Object[] {200, "h"});
        routes.put("/features/" + GROUP, new Object[] {200, "f"});
        ApiFeatureProvider provider = ApiFeatureProvider.builder(base, GROUP, body -> body.equals("h")
                        ? Map.of("collectionHash", "h1")
                        : Map.of("key", "k", "value", "true"))
                .clock(InstantSource.fixed(Instant.EPOCH))
                .build();

        assertTrue(provider.refresh());
        assertTrue(provider.isEnabled("k"));
    }
}
