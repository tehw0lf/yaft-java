package de.tehwolf.yaft;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

/**
 * "Nothing" in Java: what a disabled method without a fallback returns (R17).
 *
 * <p>{@code null} for a reference type and the zero value for a primitive --
 * returning {@code null} for an {@code int} would throw at the call site. Two
 * types get their own empty value instead of {@code null}, so a disabled
 * feature cannot break the caller:
 *
 * <ul>
 *   <li>futures ({@code CompletableFuture}, {@code CompletionStage}, {@code
 *       Future}) are already completed with {@code null}, the Java equivalent
 *       of the resolved promise R18 asks for;</li>
 *   <li>{@code Optional} and its primitive variants are empty.</li>
 * </ul>
 */
final class Nothing {

    private Nothing() {}

    static Object of(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;

        if (type == CompletableFuture.class || type == CompletionStage.class || type == Future.class) {
            return CompletableFuture.completedFuture(null);
        }

        if (type == Optional.class) return Optional.empty();
        if (type == OptionalInt.class) return OptionalInt.empty();
        if (type == OptionalLong.class) return OptionalLong.empty();
        if (type == OptionalDouble.class) return OptionalDouble.empty();

        return null;
    }
}
