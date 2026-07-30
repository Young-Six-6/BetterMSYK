package com.youngsix6.bettermsyk.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 不依赖 XposedHelpers 的轻量反射工具。
 * 用于适配LSPatch
 */
final class CompatReflect {

    private CompatReflect() {
    }

    static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw failure("class " + className, e);
        }
    }

    static Method findMethodExact(
            Class<?> type, String methodName, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method =
                        current.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                throw failure(type.getName() + "#" + methodName, t);
            }
        }
        throw failure(type.getName() + "#" + methodName,
                new NoSuchMethodException(methodName));
    }

    static Object callMethod(
            Object target, String methodName, Object... arguments) {
        if (target == null) {
            throw new IllegalArgumentException(
                    "target == null for " + methodName);
        }
        Method method = findBestMethod(
                target.getClass(), methodName, arguments, false);
        return invoke(method, target, arguments);
    }

    static Object callStaticMethod(
            Class<?> type, String methodName, Object... arguments) {
        Method method = findBestMethod(
                type, methodName, arguments, true);
        return invoke(method, null, arguments);
    }

    static Object callStaticMethodExact(
            Class<?> type, String methodName,
            Class<?>[] parameterTypes, Object... arguments) {
        Method method =
                findMethodExact(type, methodName, parameterTypes);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw failure(type.getName() + "#" + methodName,
                    new IllegalStateException("method is not static"));
        }
        return invoke(method, null, arguments);
    }

    static Object newInstance(Class<?> type, Object... arguments) {
        Constructor<?> constructor = findBestConstructor(type, arguments);
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw failure(type.getName() + " constructor", e.getCause());
        } catch (Throwable t) {
            throw failure(type.getName() + " constructor", t);
        }
    }

    static Object getField(Object target, String fieldName) {
        if (target == null) {
            throw new IllegalArgumentException(
                    "target == null for field " + fieldName);
        }
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (Throwable t) {
            throw failure(target.getClass().getName() + "#" + fieldName, t);
        }
    }

    static Object getStaticField(Class<?> type, String fieldName) {
        try {
            return findField(type, fieldName).get(null);
        } catch (Throwable t) {
            throw failure(type.getName() + "#" + fieldName, t);
        }
    }

    static int getIntField(Object target, String fieldName) {
        Object value = getField(target, fieldName);
        if (!(value instanceof Number)) {
            throw failure(target.getClass().getName() + "#" + fieldName,
                    new IllegalStateException("field is not numeric"));
        }
        return ((Number) value).intValue();
    }

    static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (Throwable t) {
            throw failure(target.getClass().getName() + "#" + fieldName, t);
        }
    }

    private static Method findBestMethod(
            Class<?> type, String methodName,
            Object[] arguments, boolean requireStatic) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        Class<?> current = type;
        while (current != null) {
            Method[] methods;
            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                throw failure(current.getName() + " methods", t);
            }
            for (Method method : methods) {
                if (!methodName.equals(method.getName())
                        || method.getParameterTypes().length
                        != arguments.length
                        || (requireStatic
                        && !Modifier.isStatic(method.getModifiers()))) {
                    continue;
                }
                int score = compatibilityScore(
                        method.getParameterTypes(), arguments);
                if (score < bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            current = current.getSuperclass();
        }
        if (best == null) {
            throw failure(type.getName() + "#" + methodName,
                    new NoSuchMethodException(
                            methodName + "(" + arguments.length + " args)"));
        }
        try {
            best.setAccessible(true);
        } catch (Throwable t) {
            throw failure(type.getName() + "#" + methodName, t);
        }
        return best;
    }

    private static Constructor<?> findBestConstructor(
            Class<?> type, Object[] arguments) {
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length != arguments.length) {
                continue;
            }
            int score = compatibilityScore(
                    constructor.getParameterTypes(), arguments);
            if (score < bestScore) {
                best = constructor;
                bestScore = score;
            }
        }
        if (best == null) {
            throw failure(type.getName() + " constructor",
                    new NoSuchMethodException(
                            "constructor(" + arguments.length + " args)"));
        }
        try {
            best.setAccessible(true);
        } catch (Throwable t) {
            throw failure(type.getName() + " constructor", t);
        }
        return best;
    }

    private static int compatibilityScore(
            Class<?>[] parameterTypes, Object[] arguments) {
        int score = 0;
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> parameterType = wrap(parameterTypes[index]);
            Object argument = arguments[index];
            if (argument == null) {
                if (parameterTypes[index].isPrimitive()) {
                    return Integer.MAX_VALUE;
                }
                score += 8;
                continue;
            }
            Class<?> argumentType = argument.getClass();
            if (parameterType.equals(argumentType)) {
                continue;
            }
            if (!parameterType.isAssignableFrom(argumentType)) {
                return Integer.MAX_VALUE;
            }
            score += inheritanceDistance(argumentType, parameterType);
        }
        return score;
    }

    private static int inheritanceDistance(
            Class<?> child, Class<?> parent) {
        if (parent.isInterface()) {
            return 2;
        }
        int distance = 1;
        Class<?> current = child.getSuperclass();
        while (current != null && !parent.equals(current)) {
            distance++;
            current = current.getSuperclass();
        }
        return distance;
    }

    private static Field findField(Class<?> type, String fieldName)
            throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + fieldName);
    }

    private static Object invoke(
            Method method, Object target, Object[] arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw failure(method.toString(), e.getCause());
        } catch (Throwable t) {
            throw failure(method.toString(), t);
        }
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        return type;
    }

    private static IllegalStateException failure(
            String operation, Throwable cause) {
        IllegalStateException failure = new IllegalStateException(
                "Reflection failed: " + operation, cause);
        ModuleLog.record("[BetterMSYK] " + failure.getMessage()
                + (cause == null ? "" : " · "
                + cause.getClass().getSimpleName() + ": "
                + cause.getMessage()));
        return failure;
    }
}
