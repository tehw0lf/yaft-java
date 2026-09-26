package de.tehwolf.yaft;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes {@link FeatureToggle} annotations take effect.
 *
 * <p>Java annotations do nothing on their own, so toggled code is reached
 * through this class, by interface:
 *
 * <pre>{@code
 * YaFT.setProvider(new LocalFeatureProvider(features));
 *
 * Algorithm algorithm = YaFT.create(Algorithm.class, NewAlgorithm.class);
 * Processing processing = YaFT.wrap(Processing.class, new Service());
 * }</pre>
 *
 * <p>Every entry point fails immediately when no provider is set (R16), so a
 * missing provider is a startup error rather than a surprise in whichever code
 * path runs first. Annotations are checked at the same point.
 */
public final class YaFT {

    /** The message of the error R16 asks for, identical to the TypeScript reference. */
    static final String NO_PROVIDER = "FeatureToggleProvider not set";

    private static volatile FeatureProvider provider;

    private YaFT() {}

    /**
     * Sets the provider every toggle asks.
     *
     * @param featureProvider the provider, or {@code null} to clear it
     */
    public static void setProvider(FeatureProvider featureProvider) {
        provider = featureProvider;
    }

    /**
     * The provider every toggle asks.
     *
     * @return the provider, or empty if none is set
     */
    public static Optional<FeatureProvider> provider() {
        return Optional.ofNullable(provider);
    }

    static FeatureProvider requireProvider() {
        FeatureProvider current = provider;
        if (current == null) throw new IllegalStateException(NO_PROVIDER);
        return current;
    }

    /**
     * Decides <em>once</em> which class stands behind {@code implementation}'s
     * toggle, and returns a supplier of instances of it (R14).
     *
     * <p>This is the equivalent of a TypeScript class decorator running when
     * the class is loaded: the toggle is read here, and changing it afterwards
     * does not affect the supplier. Call this again to decide again.
     *
     * <ul>
     *   <li>on: new instances of {@code implementation};</li>
     *   <li>off, with a {@code fallback}: new instances of the fallback;</li>
     *   <li>off, without: an empty shell whose methods all return nothing.</li>
     * </ul>
     *
     * <p>Instances whose class also toggles methods come back {@linkplain
     * #wrap wrapped}. Both classes need a no-argument constructor; both are
     * checked here, whichever the toggle selects.
     *
     * @param <I> the interface callers use
     * @param type the interface callers use
     * @param implementation the class annotated with {@link FeatureToggle}
     * @return a supplier of instances of the selected class
     * @throws IllegalStateException if no provider is set
     * @throws IllegalArgumentException if the annotation or a class is unusable
     */
    public static <I> Supplier<I> decorate(Class<I> type, Class<? extends I> implementation) {
        FeatureProvider current = requireProvider();
        requireInterface(type);

        FeatureToggle toggle = implementation.getAnnotation(FeatureToggle.class);
        if (toggle == null) {
            throw new IllegalArgumentException(implementation.getName() + " is not annotated with @FeatureToggle");
        }
        if (!toggle.fallbackMethod().isEmpty()) {
            throw new IllegalArgumentException("@FeatureToggle on class " + implementation.getName()
                    + " sets fallbackMethod, which only applies to methods; use fallback");
        }

        Constructor<? extends I> original = constructor(type, implementation);
        Constructor<? extends I> fallback = toggle.fallback() == void.class ? null : constructor(type, toggle.fallback());

        if (current.isEnabled(toggle.key())) return () -> wrap(type, instantiate(original));
        if (fallback != null) return () -> wrap(type, instantiate(fallback));

        I shell = EmptyShell.of(type, implementation);
        return () -> shell;
    }

    /**
     * Shorthand for {@code decorate(type, implementation).get()}.
     *
     * @param <I> the interface callers use
     * @param type the interface callers use
     * @param implementation the class annotated with {@link FeatureToggle}
     * @return an instance of the selected class
     * @throws IllegalStateException if no provider is set
     * @throws IllegalArgumentException if the annotation or a class is unusable
     */
    public static <I> I create(Class<I> type, Class<? extends I> implementation) {
        return decorate(type, implementation).get();
    }

    /**
     * Puts the {@link FeatureToggle}-annotated methods of {@code target} behind
     * their toggles, evaluated on <em>every call</em> (R15).
     *
     * <p>Off with a {@code fallbackMethod}: the fallback runs with the same
     * arguments on {@code target} itself (R19). Off without one: the call
     * returns nothing -- {@code null}, a primitive's zero value, a completed
     * future or an empty {@code Optional}. Methods may also be annotated on
     * the interface. If nothing is toggled, {@code target} comes back as is.
     *
     * @param <I> the interface callers use
     * @param type the interface callers use
     * @param target the instance to wrap
     * @return a proxy implementing {@code type}, or {@code target}
     * @throws IllegalStateException if no provider is set
     * @throws IllegalArgumentException if an annotation is unusable
     */
    public static <I> I wrap(Class<I> type, I target) {
        requireProvider();
        requireInterface(type);
        Objects.requireNonNull(target, "target");
        if (!type.isInstance(target)) {
            throw new IllegalArgumentException(target.getClass().getName() + " does not implement " + type.getName());
        }
        return MethodToggles.wrap(type, target);
    }

    private static void requireInterface(Class<?> type) {
        Objects.requireNonNull(type, "type");
        if (!type.isInterface()) {
            throw new IllegalArgumentException(type.getName()
                    + " is not an interface; YaFT proxies by interface, so toggled code is reached through one");
        }
    }

    private static <I> Constructor<? extends I> constructor(Class<I> type, Class<?> implementation) {
        if (!type.isAssignableFrom(implementation)) {
            throw new IllegalArgumentException(implementation.getName() + " does not implement " + type.getName());
        }
        if (implementation.isInterface() || Modifier.isAbstract(implementation.getModifiers())) {
            throw new IllegalArgumentException(implementation.getName() + " is abstract and cannot be instantiated");
        }
        try {
            Constructor<? extends I> constructor =
                    implementation.asSubclass(type).getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw new IllegalArgumentException("The constructor of " + implementation.getName()
                        + " is not accessible to YaFT; open its package to de.tehwolf.yaft");
            }
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    implementation.getName() + " needs a no-argument constructor", e);
        }
    }

    private static <I> I instantiate(Constructor<? extends I> constructor) {
        try {
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(
                    "Constructing " + constructor.getDeclaringClass().getName() + " failed", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Constructing " + constructor.getDeclaringClass().getName() + " failed", e);
        }
    }
}
