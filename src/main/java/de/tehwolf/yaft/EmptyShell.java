package de.tehwolf.yaft;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * What a disabled class without a fallback becomes: an object that still
 * answers every method of the interface, each returning {@link Nothing}
 * (R17). Calling into a disabled feature therefore never throws.
 */
final class EmptyShell implements InvocationHandler {

    private final String description;

    private EmptyShell(Class<?> implementation) {
        this.description = "YaFT empty shell for " + implementation.getName();
    }

    static <I> I of(Class<I> type, Class<?> implementation) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, new EmptyShell(implementation)));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> description;
            };
        }
        return Nothing.of(method.getReturnType());
    }
}
