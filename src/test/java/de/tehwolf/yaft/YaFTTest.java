package de.tehwolf.yaft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the conformance suite cannot express: Java's "nothing" per return
 * type, and the checks that make a broken annotation fail at decoration time
 * instead of when the toggle first goes off.
 */
class YaFTTest {

    private final Set<String> enabled = new HashSet<>();
    private FeatureProvider saved;

    @BeforeEach
    void install() {
        saved = YaFT.provider().orElse(null);
        YaFT.setProvider(enabled::contains);
    }

    @AfterEach
    void restore() {
        YaFT.setProvider(saved);
    }

    interface Values {
        int count();

        boolean flag();

        long big();

        double ratio();

        char letter();

        void act();

        Optional<String> maybe();

        OptionalInt maybeCount();

        CompletionStage<String> later();

        String name();
    }

    @FeatureToggle(key = "values")
    static final class AllValues implements Values {
        public int count() {
            return 7;
        }

        public boolean flag() {
            return true;
        }

        public long big() {
            return 7L;
        }

        public double ratio() {
            return 0.7;
        }

        public char letter() {
            return 'y';
        }

        public void act() {}

        public Optional<String> maybe() {
            return Optional.of("x");
        }

        public OptionalInt maybeCount() {
            return OptionalInt.of(7);
        }

        public CompletionStage<String> later() {
            return java.util.concurrent.CompletableFuture.completedFuture("x");
        }

        public String name() {
            return "x";
        }
    }

    @Nested
    class EmptyShell {

        @Test
        void returnsTheZeroValueForPrimitivesRatherThanThrowing() {
            Values shell = YaFT.create(Values.class, AllValues.class);

            assertEquals(0, shell.count());
            assertFalse(shell.flag());
            assertEquals(0L, shell.big());
            assertEquals(0d, shell.ratio());
            assertEquals('\0', shell.letter());
            shell.act();
        }

        @Test
        void returnsEmptyContainersAndACompletedFuture() {
            Values shell = YaFT.create(Values.class, AllValues.class);

            assertEquals(Optional.empty(), shell.maybe());
            assertEquals(OptionalInt.empty(), shell.maybeCount());
            assertTrue(shell.later().toCompletableFuture().isDone());
            assertNull(shell.later().toCompletableFuture().join());
            assertNull(shell.name());
        }

        @Test
        void hasIdentityAndSaysWhatItIs() {
            Supplier<Values> decorated = YaFT.decorate(Values.class, AllValues.class);
            Values shell = decorated.get();

            assertEquals(shell, shell);
            assertEquals(System.identityHashCode(shell), shell.hashCode());
            assertTrue(shell.toString().contains(AllValues.class.getName()));
        }

        @Test
        void isUsedWhenTheToggleIsOn() {
            enabled.add("values");
            assertEquals(7, YaFT.create(Values.class, AllValues.class).count());
        }
    }

    interface Greeter {
        String greet(String name);

        default String wave() {
            return "wave";
        }
    }

    static final class ToggledGreeter implements Greeter {
        private final String greeting = "hello ";

        @Override
        @FeatureToggle(key = "greet", fallbackMethod = "plain")
        public String greet(String name) {
            return greeting + name + "!";
        }

        private String plain(String name) {
            return greeting + name;
        }
    }

    @Nested
    class Wrap {

        @Test
        void callsAPrivateFallbackOnTheSameInstance() {
            Greeter greeter = YaFT.wrap(Greeter.class, new ToggledGreeter());

            assertEquals("hello ada", greeter.greet("ada"));
            enabled.add("greet");
            assertEquals("hello ada!", greeter.greet("ada"));
        }

        @Test
        void passesUntoggledAndDefaultMethodsThrough() {
            assertEquals("wave", YaFT.wrap(Greeter.class, new ToggledGreeter()).wave());
        }

        @Test
        void returnsTheTargetWhenNothingIsToggled() {
            Greeter plain = name -> name;
            assertSame(plain, YaFT.wrap(Greeter.class, plain));
        }

        @Test
        void readsAToggleDeclaredOnTheInterface() {
            Contract wrapped = YaFT.wrap(Contract.class, () -> "on");

            assertNull(wrapped.value());
            enabled.add("contract");
            assertEquals("on", wrapped.value());
        }

        @Test
        void rethrowsWhatTheMethodThrows() {
            Failing failing = YaFT.wrap(Failing.class, new AlwaysFailing());
            enabled.add("fail");

            IOException thrown = assertThrows(IOException.class, failing::run);
            assertEquals("boom", thrown.getMessage());
        }

        @Test
        void usesIdentityForEqualsAndDelegatesToString() {
            ToggledGreeter target = new ToggledGreeter();
            Greeter a = YaFT.wrap(Greeter.class, target);
            Greeter b = YaFT.wrap(Greeter.class, target);

            assertEquals(a, a);
            assertNotEquals(a, b);
            assertEquals(target.toString(), a.toString());
        }
    }

    interface Contract {
        @FeatureToggle(key = "contract")
        String value();
    }

    interface Failing {
        void run() throws IOException;
    }

    static final class AlwaysFailing implements Failing {
        @Override
        @FeatureToggle(key = "fail")
        public void run() throws IOException {
            throw new IOException("boom");
        }
    }

    @Nested
    class Create {

        interface Shape {
            String name();
        }

        @FeatureToggle(key = "circle", fallback = Square.class)
        static final class Circle implements Shape {
            public String name() {
                return "circle";
            }
        }

        static final class Square implements Shape {
            @Override
            @FeatureToggle(key = "rounded", fallbackMethod = "sharp")
            public String name() {
                return "rounded square";
            }

            String sharp() {
                return "square";
            }
        }

        @Test
        void wrapsTheSelectedClassWhenItTogglesMethodsToo() {
            Shape shape = YaFT.create(Shape.class, Create.Circle.class);

            assertEquals("square", shape.name());
            enabled.add("rounded");
            assertEquals("rounded square", shape.name());
        }

        @Test
        void suppliesAFreshInstanceEachTime() {
            enabled.add("circle");
            Supplier<Shape> decorated = YaFT.decorate(Shape.class, Create.Circle.class);

            assertNotSame(decorated.get(), decorated.get());
        }
    }

    @Nested
    class RejectsAtDecoration {

        interface Plain {
            String run(String input);
        }

        static final class MissingFallback implements Plain {
            @Override
            @FeatureToggle(key = "k", fallbackMethod = "nowhere")
            public String run(String input) {
                return input;
            }
        }

        static final class StaticFallback implements Plain {
            @Override
            @FeatureToggle(key = "k", fallbackMethod = "other")
            public String run(String input) {
                return input;
            }

            static String other(String input) {
                return input;
            }
        }

        static final class WrongReturnType implements Plain {
            @Override
            @FeatureToggle(key = "k", fallbackMethod = "other")
            public String run(String input) {
                return input;
            }

            Integer other(String input) {
                return 1;
            }
        }

        static final class ClassFallbackOnMethod implements Plain {
            @Override
            @FeatureToggle(key = "k", fallback = String.class)
            public String run(String input) {
                return input;
            }
        }

        static final class UnreachableToggle implements Plain {
            @Override
            public String run(String input) {
                return helper(input);
            }

            @FeatureToggle(key = "k")
            String helper(String input) {
                return input;
            }
        }

        @FeatureToggle(key = "k", fallbackMethod = "other")
        static final class MethodFallbackOnClass implements Plain {
            public String run(String input) {
                return input;
            }
        }

        @FeatureToggle(key = "k", fallback = String.class)
        static final class ForeignFallback implements Plain {
            public String run(String input) {
                return input;
            }
        }

        @FeatureToggle(key = "k", fallback = NoDefaultConstructor.class)
        static final class FallbackWithoutConstructor implements Plain {
            public String run(String input) {
                return input;
            }
        }

        static final class NoDefaultConstructor implements Plain {
            NoDefaultConstructor(String unused) {}

            public String run(String input) {
                return input;
            }
        }

        static final class NotAnnotated implements Plain {
            public String run(String input) {
                return input;
            }
        }

        @Test
        void aMissingFallbackMethod() {
            assertRejected(() -> YaFT.wrap(Plain.class, new MissingFallback()), "not found");
        }

        @Test
        void aStaticFallbackMethod() {
            assertRejected(() -> YaFT.wrap(Plain.class, new StaticFallback()), "static");
        }

        @Test
        void aFallbackMethodWithAnIncompatibleReturnType() {
            assertRejected(() -> YaFT.wrap(Plain.class, new WrongReturnType()), "java.lang.Integer");
        }

        @Test
        void aClassFallbackOnAMethod() {
            assertRejected(() -> YaFT.wrap(Plain.class, new ClassFallbackOnMethod()), "use fallbackMethod");
        }

        @Test
        void aToggleTheInterfaceCannotReach() {
            // Ignoring it would leave the helper permanently on.
            assertRejected(() -> YaFT.wrap(Plain.class, new UnreachableToggle()), "has no effect");
        }

        @Test
        void aMethodFallbackOnAClass() {
            assertRejected(() -> YaFT.decorate(Plain.class, MethodFallbackOnClass.class), "use fallback");
        }

        @Test
        void aFallbackClassOfTheWrongType() {
            assertRejected(() -> YaFT.decorate(Plain.class, ForeignFallback.class), "does not implement");
        }

        @Test
        void aFallbackClassWithoutANoArgumentConstructor() {
            // Checked although the toggle is on and selects the original.
            enabled.add("k");
            assertRejected(
                    () -> YaFT.decorate(Plain.class, FallbackWithoutConstructor.class), "no-argument constructor");
        }

        @Test
        void aClassWithoutTheAnnotation() {
            assertRejected(() -> YaFT.decorate(Plain.class, NotAnnotated.class), "not annotated");
        }

        /** What Spring's CGLIB proxy of a toggled bean looks like: a subclass overriding without the annotation. */
        static class ProxiedLikeCglib extends ToggledPlain {
            @Override
            public String run(String input) {
                return super.run(input);
            }
        }

        static class ToggledPlain implements Plain {
            @Override
            @FeatureToggle(key = "k")
            public String run(String input) {
                return input;
            }
        }

        @Test
        void aToggleHiddenByAnOverrideAsInASubclassProxy() {
            assertRejected(() -> YaFT.wrap(Plain.class, new ProxiedLikeCglib()), "hidden by");
        }

        @Test
        void aJdkProxy() {
            Plain proxy = (Plain) java.lang.reflect.Proxy.newProxyInstance(
                    Plain.class.getClassLoader(), new Class<?>[] {Plain.class}, (p, m, a) -> "x");
            assertRejected(() -> YaFT.wrap(Plain.class, proxy), "JDK proxy");
        }

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void aTypeThatIsNotAnInterface() {
            Class raw = NotAnnotated.class;
            assertRejected(() -> YaFT.wrap(raw, new NotAnnotated()), "not an interface");
        }

        private void assertRejected(Runnable decoration, String reason) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, decoration::run);
            assertTrue(error.getMessage().contains(reason), error.getMessage());
        }
    }
}
