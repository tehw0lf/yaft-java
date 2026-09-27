package de.tehwolf.yaft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProvidersTest {

    @Test
    void featureProviderEvaluatesAgainstTheInjectedClock() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-18T11:59:59Z"));
        InstantSource clock = now::get;
        LocalFeatureProvider provider = new LocalFeatureProvider(
                Map.of("f", new Feature("f", "true", "2026-09-18T12:00:00Z", "", List.of())), clock);

        assertFalse(provider.isEnabled("f"));
        now.set(Instant.parse("2026-09-18T12:00:00Z"));
        assertTrue(provider.isEnabled("f"));
    }

    @Test
    void featureProviderAnswersFalseForNullsInsteadOfThrowing() {
        Map<String, Feature> data = new HashMap<>();
        data.put("f", null);
        LocalFeatureProvider provider = new LocalFeatureProvider(data);

        assertFalse(provider.isEnabled("f"));
        assertFalse(provider.isEnabled(null));
    }

    @Test
    void featureProviderReadsABackendResponse() {
        LocalFeatureProvider provider = LocalFeatureProvider.fromResponse(Map.of(
                "toggles", List.of(Map.of("Key", "uuid|a", "Value", "true"), Map.of("key", "uuid|b", "value", "false"))));

        assertTrue(provider.isEnabled("uuid|a"));
        assertFalse(provider.isEnabled("uuid|b"));
    }

    @Test
    void providersCopyTheirDataAndReplaceIt() {
        Map<String, Boolean> data = new HashMap<>(Map.of("t", true));
        LocalBooleanProvider provider = new LocalBooleanProvider(data);

        data.put("t", false);
        assertTrue(provider.isEnabled("t"));
        assertThrows(UnsupportedOperationException.class, () -> provider.data().put("x", true));

        provider.replace(Map.of("t", false));
        assertFalse(provider.isEnabled("t"));
    }

    @Test
    void booleanProviderDropsWhatIsNotABoolean() {
        Map<String, Object> response = new HashMap<>();
        response.put("on", true);
        response.put("text", "true");
        response.put("number", 1);
        response.put("nothing", null);
        LocalBooleanProvider provider = LocalBooleanProvider.fromResponse(response);

        assertEquals(Map.of("on", true), provider.data());
        assertFalse(provider.isEnabled("text"));
        assertFalse(provider.isEnabled(null));
    }

    @Test
    void featureCopiesItsTags() {
        List<String> tags = new ArrayList<>(List.of("beta"));
        Feature feature = new Feature("f", "true", "", "", tags);

        tags.add("late");
        assertEquals(List.of("beta"), feature.tags());
        assertEquals(List.of(), new Feature("f", "true", "", "", null).tags());
    }
}
