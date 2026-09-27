package de.tehwolf.yaft;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts a class or a method behind a feature toggle.
 *
 * <p>An annotation alone changes nothing at runtime; it takes effect through
 * {@link YaFT}. When it is evaluated is fixed by the spec and observable:
 *
 * <ul>
 *   <li><b>Class</b> -- once, in {@link YaFT#decorate}, like a TypeScript
 *       class decorator at load time (R14). The decision holds for every
 *       instance the returned supplier creates.</li>
 *   <li><b>Method</b> -- on every call through the proxy {@link YaFT#wrap}
 *       returns (R15).</li>
 * </ul>
 *
 * <pre>{@code
 * @FeatureToggle(key = "new-algorithm", fallback = OldAlgorithm.class)
 * class NewAlgorithm implements Algorithm { ... }
 *
 * class Service implements Processing {
 *     @FeatureToggle(key = "enhanced", fallbackMethod = "basic")
 *     public String process(String input) { ... }
 *     String basic(String input) { ... }
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface FeatureToggle {

    /**
     * The feature's key, as the provider knows it.
     *
     * @return the key
     */
    String key();

    /**
     * Classes only: the class to use while the toggle is off. It must
     * implement the same interface and have a no-argument constructor. Without
     * one, a disabled class becomes an empty shell whose methods all return
     * nothing.
     *
     * @return the fallback class, or {@code void.class} for none
     */
    Class<?> fallback() default void.class;

    /**
     * Methods only: the name of a method on the same class to call while the
     * toggle is off, with the same arguments on the same instance (R19). It
     * must take the same parameter types and return a compatible type. Without
     * one, a disabled method returns nothing.
     *
     * @return the fallback method's name, or {@code ""} for none
     */
    String fallbackMethod() default "";
}
