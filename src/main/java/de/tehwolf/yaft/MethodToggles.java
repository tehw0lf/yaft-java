package de.tehwolf.yaft;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The proxy behind {@link YaFT#wrap}: every call to a toggled method asks the
 * provider again (R15) and routes to the original, the fallback or {@link
 * Nothing}.
 *
 * <p>Everything that can be wrong with an annotation is checked when the proxy
 * is built, so a misconfiguration fails at startup rather than on the first
 * call with the toggle off.
 */
final class MethodToggles implements InvocationHandler {

    /**
     * How one interface method is served. {@code key} is null for an
     * untoggled method.
     *
     * <p>{@code method} is the interface method made accessible here. The
     * proxy hands {@link #invoke} a different {@code Method} object for the
     * same method, and the accessible flag belongs to the object, so invoking
     * that one fails for a non-public interface.
     */
    private record Route(Method method, String key, Method fallback) {}

    private final Object target;
    private final Map<Method, Route> routes;

    private MethodToggles(Object target, Map<Method, Route> routes) {
        this.target = target;
        this.routes = routes;
    }

    /**
     * Builds the proxy, or returns {@code target} itself when none of its
     * methods is toggled.
     */
    static <I> I wrap(Class<I> type, I target) {
        Class<?> implementation = target.getClass();
        // Found with yaft-java-playground: wrapping a bean that Spring had
        // already proxied left every toggle silently on, because the proxy
        // class carries none of the annotations. The same holds for any JDK
        // proxy, so it is refused rather than wrapped blind.
        if (Proxy.isProxyClass(implementation)) {
            throw new IllegalArgumentException(implementation.getName() + " is a JDK proxy, and YaFT cannot see"
                    + " the annotations of the object behind it. " + WRAP_FIRST);
        }
        Map<Method, Route> routes = new HashMap<>();
        boolean toggled = false;

        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            makeAccessible(method);

            Method original = findMethod(implementation, method.getName(), method.getParameterTypes());
            FeatureToggle toggle = original != null ? original.getAnnotation(FeatureToggle.class) : null;
            if (original != null && toggle == null) rejectShadowedToggle(original);
            if (toggle == null) toggle = method.getAnnotation(FeatureToggle.class);

            if (toggle == null) {
                routes.put(method, new Route(method, null, null));
                continue;
            }
            toggled = true;
            routes.put(method, new Route(method, toggle.key(), fallbackFor(method, toggle, implementation)));
        }

        rejectUnreachableToggles(type, implementation);

        if (!toggled) return target;
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, new MethodToggles(target, routes)));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> target.toString();
            };
        }

        Route route = routes.get(method);
        if (route.key() == null) return call(route.method(), args);

        if (YaFT.requireProvider().isEnabled(route.key())) return call(route.method(), args);
        if (route.fallback() != null) return call(route.fallback(), args);
        return Nothing.of(method.getReturnType());
    }

    /** Invokes on the wrapped instance, so a fallback sees the same receiver (R19). */
    private Object call(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method fallbackFor(Method method, FeatureToggle toggle, Class<?> implementation) {
        String where = implementation.getName() + "." + method.getName();
        if (toggle.fallback() != void.class) {
            throw new IllegalArgumentException(
                    "@FeatureToggle on " + where + " sets fallback, which only applies to classes; use fallbackMethod");
        }
        if (toggle.fallbackMethod().isEmpty()) return null;

        Method fallback = findMethod(implementation, toggle.fallbackMethod(), method.getParameterTypes());
        if (fallback == null) {
            throw new IllegalArgumentException("Fallback method " + toggle.fallbackMethod()
                    + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                            .collect(Collectors.joining(", ", "(", ")"))
                    + " for " + where + " not found; it needs the same parameter types");
        }
        if (Modifier.isStatic(fallback.getModifiers())) {
            throw new IllegalArgumentException(
                    "Fallback method " + toggle.fallbackMethod() + " for " + where
                            + " is static; it must be an instance method so it runs on the same receiver");
        }
        if (!returns(method.getReturnType(), fallback.getReturnType())) {
            throw new IllegalArgumentException("Fallback method " + toggle.fallbackMethod() + " for " + where
                    + " returns " + fallback.getReturnType().getName() + ", which is not a "
                    + method.getReturnType().getName());
        }
        makeAccessible(fallback);
        return fallback;
    }

    private static boolean returns(Class<?> expected, Class<?> actual) {
        if (expected.isPrimitive() || actual.isPrimitive()) return expected == actual;
        return expected.isAssignableFrom(actual);
    }

    private static final String WRAP_FIRST = "Wrap the object itself, before another framework proxies it"
            + " -- with Spring, call YaFT.wrap in the @Bean method; Spring can then advise the YaFT proxy.";

    /**
     * A toggle declared on a superclass method but overridden without the
     * annotation would be silently dropped. That is exactly what a generated
     * subclass proxy (Spring CGLIB, Hibernate, ByteBuddy) looks like -- and
     * even if YaFT used the superclass annotation, a fallback would run on the
     * proxy instance, whose fields are empty, instead of on the real object.
     */
    private static void rejectShadowedToggle(Method override) {
        for (Class<?> c = override.getDeclaringClass().getSuperclass(); c != null; c = c.getSuperclass()) {
            try {
                Method hidden = c.getDeclaredMethod(override.getName(), override.getParameterTypes());
                if (hidden.isAnnotationPresent(FeatureToggle.class)) {
                    throw new IllegalArgumentException("@FeatureToggle on " + c.getName() + "." + hidden.getName()
                            + " is hidden by " + override.getDeclaringClass().getName() + "." + override.getName()
                            + ", which overrides it without the annotation. If that class is a generated proxy: "
                            + WRAP_FIRST + " Otherwise repeat the annotation on the override.");
                }
            } catch (NoSuchMethodException e) {
                // not declared here; keep looking
            }
        }
    }

    /**
     * An annotation on a method the interface does not have can never take
     * effect, because the proxy only sees interface calls. Silently ignoring
     * it would leave a feature permanently on.
     */
    private static void rejectUnreachableToggles(Class<?> type, Class<?> implementation) {
        Set<String> names = Arrays.stream(type.getMethods()).map(Method::getName).collect(Collectors.toSet());
        for (Class<?> c = implementation; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.isAnnotationPresent(FeatureToggle.class) && !names.contains(method.getName())) {
                    throw new IllegalArgumentException("@FeatureToggle on " + c.getName() + "." + method.getName()
                            + " has no effect: " + type.getName() + " has no such method");
                }
            }
        }
    }

    /** Looks through the class hierarchy, so private and inherited methods are found too. */
    private static Method findMethod(Class<?> implementation, String name, Class<?>[] parameterTypes) {
        for (Class<?> c = implementation; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                // keep looking in the superclass
            }
        }
        return null;
    }

    private static void makeAccessible(Method method) {
        if (!method.trySetAccessible()) {
            throw new IllegalArgumentException(method.getDeclaringClass().getName() + "." + method.getName()
                    + " is not accessible to YaFT; open its package to de.tehwolf.yaft");
        }
    }
}
