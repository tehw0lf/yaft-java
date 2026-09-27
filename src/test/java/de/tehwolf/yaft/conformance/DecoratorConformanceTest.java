package de.tehwolf.yaft.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.tehwolf.yaft.FeatureProvider;
import de.tehwolf.yaft.FeatureToggle;
import de.tehwolf.yaft.YaFT;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;

/**
 * Runs the shared decorator cases against this port.
 *
 * <p>These are not input/output pairs -- "the fallback receives the same
 * receiver" cannot be expressed as JSON. Each case names a scenario and an
 * outcome in port-neutral terms, and this class maps them onto Java: a class
 * toggle is {@link YaFT#decorate}, a method toggle is {@link YaFT#wrap}, and
 * "nothing" is {@code null} (a completed future for an async method).
 *
 * <p>An unknown {@code target}, {@code toggle}, {@code fallback}, {@code
 * expected} or assertion fails the case instead of skipping it.
 */
class DecoratorConformanceTest {

    static final String KEY = "conformanceToggle";
    static final String ORIGINAL = "original";
    static final String FALLBACK = "fallback";

    private FeatureProvider saved;

    @BeforeEach
    void save() {
        saved = YaFT.provider().orElse(null);
    }

    @AfterEach
    void restore() {
        YaFT.setProvider(saved);
    }

    @TestFactory
    Stream<DynamicTest> decorator() {
        return Cases.load("decorator").stream().map(c -> DynamicTest.dynamicTest(Cases.title(c), run(c)));
    }

    private Executable run(Map<String, Object> c) {
        return () -> {
            try {
                String toggle = (String) c.get("toggle");
                if (toggle.equals("no-provider")) {
                    noProvider(c);
                    return;
                }
                Switchable provider = setUp(toggle, c);
                switch ((String) c.get("target")) {
                    case "method" -> methodCase(c, provider);
                    case "async-method" -> asyncMethodCase(c, provider);
                    case "class" -> classCase(c, provider);
                    default -> throw Cases.unsupported("target", c.get("target"), c.get("name"));
                }
            } finally {
                YaFT.setProvider(saved);
            }
        };
    }

    // --- method --------------------------------------------------------------

    interface Runner {
        String run(String a, int b);
    }

    /** What ran, and what the fallback was handed. */
    static final class Recorder {
        String ran = "";
        List<Object> args = List.of();
        Object receiver;
    }

    static final class MethodWithFallback implements Runner {
        final Recorder recorder;

        MethodWithFallback(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        @FeatureToggle(key = KEY, fallbackMethod = "fallback")
        public String run(String a, int b) {
            recorder.ran = ORIGINAL;
            return ORIGINAL;
        }

        String fallback(String a, int b) {
            recorder.ran = FALLBACK;
            recorder.args = List.of(a, b);
            recorder.receiver = this;
            return FALLBACK;
        }
    }

    static final class MethodWithoutFallback implements Runner {
        final Recorder recorder;

        MethodWithoutFallback(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        @FeatureToggle(key = KEY)
        public String run(String a, int b) {
            recorder.ran = ORIGINAL;
            return ORIGINAL;
        }
    }

    private void methodCase(Map<String, Object> c, Switchable provider) {
        Recorder recorder = new Recorder();
        Runner instance = switch ((String) c.get("fallback")) {
            case "method" -> new MethodWithFallback(recorder);
            case "none" -> new MethodWithoutFallback(recorder);
            default -> throw Cases.unsupported("fallback", c.get("fallback") + " on a method target", c.get("name"));
        };

        Runner wrapped = YaFT.wrap(Runner.class, instance);
        flipIfTwoPhase((String) c.get("toggle"), provider);
        String result = wrapped.run("a", 1);

        switch ((String) c.get("expected")) {
            case "original" -> {
                assertEquals(ORIGINAL, recorder.ran);
                assertEquals(ORIGINAL, result);
            }
            case "fallback" -> {
                assertEquals(FALLBACK, recorder.ran);
                assertEquals(FALLBACK, result);
            }
            case "nothing" -> {
                assertEquals("", recorder.ran);
                assertNull(result);
            }
            default -> throw Cases.unsupported("expected", c.get("expected"), c.get("name"));
        }

        @SuppressWarnings("unchecked")
        List<String> assertions = (List<String>) c.getOrDefault("assertions", List.of());
        for (String assertion : assertions) {
            switch (assertion) {
                case "same-arguments" -> assertEquals(List.of("a", 1), recorder.args);
                // The instance itself, not the proxy: that is the object whose
                // state the original would have read.
                case "same-receiver" -> assertSame(instance, recorder.receiver);
                default -> throw Cases.unsupported("assertion", assertion, c.get("name"));
            }
        }
    }

    // --- async method --------------------------------------------------------

    interface AsyncRunner {
        CompletableFuture<String> run();
    }

    static final class AsyncSubject implements AsyncRunner {
        final Recorder recorder;

        AsyncSubject(Recorder recorder) {
            this.recorder = recorder;
        }

        @Override
        @FeatureToggle(key = KEY)
        public CompletableFuture<String> run() {
            recorder.ran = ORIGINAL;
            return CompletableFuture.completedFuture(ORIGINAL);
        }
    }

    private void asyncMethodCase(Map<String, Object> c, Switchable provider) {
        if (!"none".equals(c.get("fallback"))) {
            throw Cases.unsupported("fallback", c.get("fallback") + " on an async-method target", c.get("name"));
        }
        Recorder recorder = new Recorder();
        AsyncRunner wrapped = YaFT.wrap(AsyncRunner.class, new AsyncSubject(recorder));
        flipIfTwoPhase((String) c.get("toggle"), provider);
        CompletableFuture<String> result = wrapped.run();

        switch ((String) c.get("expected")) {
            case "original" -> {
                assertEquals(ORIGINAL, result.join());
                assertEquals(ORIGINAL, recorder.ran);
            }
            case "resolved-nothing" -> {
                // A join() at the call site must not break, so the empty
                // result is an already-completed future, never null (R18).
                assertNotNull(result);
                assertTrue(result.isDone());
                assertNull(result.join());
                assertEquals("", recorder.ran);
            }
            default -> throw Cases.unsupported("expected", c.get("expected"), c.get("name"));
        }
    }

    // --- class ---------------------------------------------------------------

    interface Which {
        String which();
    }

    @FeatureToggle(key = KEY, fallback = FallbackClass.class)
    static final class ClassWithFallback implements Which {
        @Override
        public String which() {
            return ORIGINAL;
        }
    }

    @FeatureToggle(key = KEY)
    static final class ClassWithoutFallback implements Which {
        @Override
        public String which() {
            return ORIGINAL;
        }
    }

    static final class FallbackClass implements Which {
        @Override
        public String which() {
            return FALLBACK;
        }
    }

    private void classCase(Map<String, Object> c, Switchable provider) {
        Class<? extends Which> subject = switch ((String) c.get("fallback")) {
            case "class" -> ClassWithFallback.class;
            case "none" -> ClassWithoutFallback.class;
            default -> throw Cases.unsupported("fallback", c.get("fallback") + " on a class target", c.get("name"));
        };

        Supplier<Which> decorated = YaFT.decorate(Which.class, subject);
        // A class is decided when it is decorated, so this flip must have no
        // effect -- exactly what the two-phase cases check.
        flipIfTwoPhase((String) c.get("toggle"), provider);
        Which instance = decorated.get();

        switch ((String) c.get("expected")) {
            case "original" -> {
                assertTrue(subject.isInstance(instance));
                assertEquals(ORIGINAL, instance.which());
            }
            case "fallback" -> {
                assertTrue(instance instanceof FallbackClass);
                assertEquals(FALLBACK, instance.which());
            }
            case "empty-shell" -> {
                // Still constructs and still answers every method, each
                // returning nothing, so calling into it does not throw.
                assertFalse(subject.isInstance(instance));
                assertNull(instance.which());
            }
            default -> throw Cases.unsupported("expected", c.get("expected"), c.get("name"));
        }
    }

    // --- no provider ---------------------------------------------------------

    /** Decorating itself must fail, not the first call (R16). */
    private void noProvider(Map<String, Object> c) {
        if (!"decoration-error".equals(c.get("expected"))) {
            throw Cases.unsupported("expected", c.get("expected"), c.get("name"));
        }
        YaFT.setProvider(null);

        Executable decorate = switch ((String) c.get("target")) {
            case "method" -> () -> YaFT.wrap(Runner.class, new MethodWithoutFallback(new Recorder()));
            case "async-method" -> () -> YaFT.wrap(AsyncRunner.class, new AsyncSubject(new Recorder()));
            case "class" -> () -> YaFT.decorate(Which.class, ClassWithoutFallback.class);
            default -> throw Cases.unsupported("target", c.get("target"), c.get("name"));
        };
        IllegalStateException error = assertThrows(IllegalStateException.class, decorate);
        assertEquals("FeatureToggleProvider not set", error.getMessage());
    }

    // --- provider ------------------------------------------------------------

    /** A provider whose answer can change between decoration and call. */
    static final class Switchable implements FeatureProvider {
        volatile boolean enabled;

        Switchable(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean isEnabled(String key) {
            return enabled;
        }
    }

    /**
     * {@code toggle} also encodes when the value changes: {@code on-then-off}
     * is on while decorating and off by the time of use, which is what
     * separates "evaluated once" from "evaluated per call".
     */
    private static Switchable setUp(String toggle, Map<String, Object> c) {
        Switchable provider = switch (toggle) {
            case "on", "on-then-off" -> new Switchable(true);
            case "off", "off-then-on" -> new Switchable(false);
            default -> throw Cases.unsupported("toggle", toggle, c.get("name"));
        };
        YaFT.setProvider(provider);
        return provider;
    }

    private static void flipIfTwoPhase(String toggle, Switchable provider) {
        if (toggle.equals("on-then-off")) provider.enabled = false;
        if (toggle.equals("off-then-on")) provider.enabled = true;
    }
}
