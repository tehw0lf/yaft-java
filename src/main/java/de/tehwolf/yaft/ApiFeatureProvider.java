package de.tehwolf.yaft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A feature-shape provider that loads a toggle group from a YaFT backend.
 *
 * <p>{@link #refresh()} asks {@code GET /collectionHash/{uuid}} whether the
 * group changed and only then fetches {@code GET /features/{uuid}}. The
 * response goes through {@link Mapping#normaliseCollection}, so every envelope
 * and field spelling the backend has sent is understood, and evaluation is
 * local, against the injected clock (R26): a scheduled toggle flips here at
 * the exact instant, not when the backend's cron job gets to it.
 *
 * <p>The provider does not parse JSON itself. It is handed a {@link
 * JsonDecoder} -- in practice a method reference to the application's own
 * Jackson or Gson instance -- so the library has no dependency to conflict
 * with the application's, and no hand-written parser.
 *
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 * ApiFeatureProvider provider = ApiFeatureProvider
 *         .builder(URI.create("https://yaft.tehwolf.de"), groupUuid, body -> mapper.readValue(body, Object.class))
 *         .build();
 * provider.refresh();
 * scheduler.scheduleWithFixedDelay(provider::refreshQuietly, 30, 30, TimeUnit.SECONDS);
 * }</pre>
 *
 * <p>Nothing is fetched until the first {@code refresh()}; until then every
 * feature is off. A failed refresh throws and keeps the data from the last
 * successful one, so a backend outage does not switch everything off.
 *
 * <p>Thread-safe.
 */
public final class ApiFeatureProvider implements FeatureProvider {

    /** Parses a response body into the {@code Map}/{@code List}/{@code String} tree JSON libraries produce. */
    @FunctionalInterface
    public interface JsonDecoder {

        /**
         * Parses {@code body}.
         *
         * @param body the response body
         * @return the parsed tree
         * @throws Exception if {@code body} is not valid JSON
         */
        Object decode(String body) throws Exception;
    }

    private static final System.Logger LOG = System.getLogger("de.tehwolf.yaft");

    private final String keyPrefix;
    private final URI features;
    private final URI collectionHash;
    private final JsonDecoder json;
    private final HttpClient client;
    private final Duration timeout;
    private final long maxBodyBytes;
    private final InstantSource clock;

    private volatile Map<String, Feature> data = Map.of();
    private String hash;

    private ApiFeatureProvider(Builder builder) {
        String base = builder.apiUrl.toString().replaceAll("/+$", "");
        this.keyPrefix = builder.group + "|";
        this.features = URI.create(base + "/features/" + builder.group);
        this.collectionHash = URI.create(base + "/collectionHash/" + builder.group);
        this.json = builder.json;
        this.client = builder.client != null
                ? builder.client
                : HttpClient.newBuilder()
                        .connectTimeout(builder.timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.timeout = builder.timeout;
        this.maxBodyBytes = builder.maxBodyBytes;
        this.clock = builder.clock;
    }

    /**
     * Starts a provider for one toggle group.
     *
     * @param apiUrl the backend's base URL, {@code http} or {@code https}
     * @param groupUuid the group's UUID, as returned when its first toggle was created
     * @param json parses a response body; see {@link JsonDecoder}
     * @return a builder for the remaining, optional settings
     * @throws IllegalArgumentException if the URL or the UUID is unusable
     */
    public static Builder builder(URI apiUrl, String groupUuid, JsonDecoder json) {
        return new Builder(apiUrl, groupUuid, json);
    }

    /**
     * Fetches the group if it changed since the last successful refresh.
     *
     * @return {@code true} if new data was loaded, {@code false} if the group was unchanged
     * @throws IOException if the backend cannot be reached, answers with anything
     *     but {@code 200}, or sends a body that is too large or does not parse;
     *     the previous data stays in place
     * @throws InterruptedException if interrupted while waiting for the backend
     */
    public synchronized boolean refresh() throws IOException, InterruptedException {
        String current = hashOf(get(collectionHash));
        if (current.equals(hash)) return false;

        data = groupFrom(get(features));
        hash = current;
        return true;
    }

    /**
     * Accepts a {@code /features} body only if it is recognisably a group: a
     * collection envelope, even an empty one, or a single toggle. Anything
     * else -- null, an array, a proxy's error object, a collection whose
     * entries are all unusable -- would normalise to nothing, and storing
     * that would switch every feature off without an error, while the
     * recorded hash kept it that way. Individual unusable entries next to
     * good ones are still skipped (R25).
     */
    private Map<String, Feature> groupFrom(Object response) throws IOException {
        if (!(response instanceof Map<?, ?> body)) {
            throw new IOException("GET " + features + " sent a body that is not a JSON object");
        }
        Map<String, Feature> group = Mapping.normaliseCollection(body);
        if (!group.isEmpty()) return group;

        Object collection = body.get("toggles") instanceof List<?> ? body.get("toggles") : body.get("value");
        if (!(collection instanceof List<?> entries)) {
            throw new IOException("GET " + features + " sent a body that holds no toggles");
        }
        if (!entries.isEmpty()) {
            throw new IOException("GET " + features + " sent " + entries.size() + " entries, none a usable toggle");
        }
        return group;
    }

    /**
     * Like {@link #refresh()}, but logs a failure instead of throwing, for use
     * from a scheduler. The previous data stays in place.
     *
     * @return {@code true} if new data was loaded
     */
    public boolean refreshQuietly() {
        try {
            return refresh();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "YaFT: refreshing " + features + " failed; keeping the previous data", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The data of the last successful refresh.
     *
     * @return an unmodifiable view of the features by key
     */
    public Map<String, Feature> data() {
        return data;
    }

    /**
     * Answers for a toggle of this group, by its name or by its full key.
     *
     * <p>The backend keys every toggle as {@code <group uuid>|<name>}. The UUID
     * only exists at runtime, and an annotation value must be a compile-time
     * constant, so {@code @FeatureToggle(key = "newCheckout")} could never
     * name the full key. A key is therefore looked up as given first, and
     * then as a name within this provider's group.
     *
     * @param key the toggle's name, or its full {@code uuid|name} key
     * @return {@code true} if the toggle is on
     */
    @Override
    public boolean isEnabled(String key) {
        if (key == null) return false;
        Feature feature = data.get(key);
        if (feature == null && !key.startsWith(keyPrefix)) feature = data.get(keyPrefix + key);
        return Evaluation.evaluate(feature, clock.instant());
    }

    private Object get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        // HttpRequest.timeout only bounds the wait for the headers. A server
        // that sends them and then trickles or withholds the body would block
        // a plain read forever -- and refresh() holds the lock meanwhile -- so
        // the whole exchange, body included, runs against one deadline.
        CompletableFuture<HttpResponse<byte[]>> exchange = client.sendAsync(
                request, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maxBodyBytes));
        HttpResponse<byte[]> response;
        try {
            response = exchange.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new HttpTimeoutException("GET " + uri + " did not complete within " + timeout);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                // BodyHandlers.limiting reports an oversized body by message
                // only. Should that text change, the IOException still
                // propagates, just less readably -- rejectsAnOversizedBody
                // would flag it.
                if (io.getMessage() != null && io.getMessage().contains("exceeds capacity")) {
                    throw new IOException("GET " + uri + " sent more than " + maxBodyBytes + " bytes", io);
                }
                throw io;
            }
            throw new IOException("GET " + uri + " failed", e.getCause());
        }

        if (response.statusCode() != 200) {
            throw new IOException("GET " + uri + " answered " + response.statusCode());
        }
        try {
            return json.decode(new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("GET " + uri + " sent a body that does not parse", e);
        }
    }

    /** Reads the hash by presence, as the TypeScript reference does: {@code collectionHash}, else {@code value}. */
    private String hashOf(Object response) throws IOException {
        if (response instanceof Map<?, ?> body) {
            Object value = body.containsKey("collectionHash") ? body.get("collectionHash") : body.get("value");
            if (value instanceof String s && !s.isEmpty()) return s;
        }
        throw new IOException("GET " + collectionHash + " sent no collectionHash");
    }

    /** Optional settings for an {@link ApiFeatureProvider}. */
    public static final class Builder {

        private final URI apiUrl;
        private final String group;
        private final JsonDecoder json;
        private HttpClient client;
        private Duration timeout = Duration.ofSeconds(5);
        private long maxBodyBytes = 1024 * 1024;
        private InstantSource clock = InstantSource.system();

        private Builder(URI apiUrl, String groupUuid, JsonDecoder json) {
            Objects.requireNonNull(apiUrl, "apiUrl");
            String scheme = apiUrl.getScheme() == null ? "" : apiUrl.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                throw new IllegalArgumentException("apiUrl must be http or https: " + apiUrl);
            }
            if (apiUrl.getRawQuery() != null || apiUrl.getRawFragment() != null) {
                throw new IllegalArgumentException("apiUrl must not carry a query or fragment: " + apiUrl);
            }
            this.apiUrl = apiUrl;
            this.group = canonicalUuid(groupUuid);
            this.json = Objects.requireNonNull(json, "json");
        }

        /**
         * The HTTP client to use, for example one with a proxy or custom TLS.
         * Its own redirect and connect-timeout settings then apply; the default
         * client follows no redirects.
         *
         * @param client the client
         * @return this builder
         */
        public Builder client(HttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
            return this;
        }

        /**
         * How long one request may take, from connecting until the last byte
         * of the body. Default 5 seconds.
         *
         * @param timeout the timeout, positive
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
            this.timeout = timeout;
            return this;
        }

        /**
         * The largest response body accepted. Default 1 MiB, far above any
         * real group; the limit keeps a misbehaving endpoint from exhausting
         * memory.
         *
         * @param bytes the limit, positive
         * @return this builder
         */
        public Builder maxBodyBytes(long bytes) {
            if (bytes <= 0) throw new IllegalArgumentException("maxBodyBytes must be positive");
            this.maxBodyBytes = bytes;
            return this;
        }

        /**
         * The source of "now" for evaluation. Default the system clock.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(InstantSource clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Builds the provider. Nothing is fetched yet.
         *
         * @return the provider
         */
        public ApiFeatureProvider build() {
            return new ApiFeatureProvider(this);
        }

        /**
         * The UUID goes into the request path, so it is checked strictly:
         * only the canonical 8-4-4-4-12 hex form, compared after a round trip
         * through {@link UUID}, which also rejects {@code /}, {@code ..} and
         * percent-encoding.
         */
        private static String canonicalUuid(String groupUuid) {
            Objects.requireNonNull(groupUuid, "groupUuid");
            try {
                String canonical = UUID.fromString(groupUuid).toString();
                if (canonical.equalsIgnoreCase(groupUuid)) return canonical;
            } catch (IllegalArgumentException e) {
                // reported below
            }
            throw new IllegalArgumentException("groupUuid is not a UUID: " + groupUuid);
        }
    }
}
