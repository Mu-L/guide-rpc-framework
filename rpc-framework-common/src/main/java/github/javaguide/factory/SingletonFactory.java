package github.javaguide.factory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 获取单例对象的工厂类
 *
 * @author shuang.kou
 * @createTime 2020年06月03日 15:04:00
 */
public final class SingletonFactory {
    private static final Map<Class<?>, Object> OBJECT_MAP = new ConcurrentHashMap<>();

    private SingletonFactory() {
    }

    public static <T> T getInstance(Supplier<T> constructor, Class<T> c) {
        Objects.requireNonNull(constructor, "Constructor cannot be null");
        Objects.requireNonNull(c, "Class cannot be null");
        return getOrCreate(c,
                () -> Objects.requireNonNull(constructor.get(), "Constructor returned null"));
    }

    public static <T> T getInstance(Consumer<T> initConsumer, Class<T> c) {
        Objects.requireNonNull(initConsumer, "Initializer cannot be null");
        Objects.requireNonNull(c, "Class cannot be null");
        return getOrCreate(c, () -> {
            T instance = newInstance(c);
            initConsumer.accept(instance);
            return instance;
        });
    }


    public static <T> T getInstance(Class<T> c) {
        Objects.requireNonNull(c, "Class cannot be null");
        return getOrCreate(c, () -> newInstance(c));
    }

    @Deprecated
    public static <T> T getInstanceOld(Class<T> c) {
        return getInstance(c);
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                 | NoSuchMethodException e) {
            throw new IllegalStateException("Failed to create singleton: " + type.getName(), e);
        }
    }

    private static <T> T getOrCreate(Class<T> type, Supplier<T> constructor) {
        Object instance = OBJECT_MAP.get(type);
        if (instance != null) {
            return type.cast(instance);
        }
        synchronized (OBJECT_MAP) {
            instance = OBJECT_MAP.get(type);
            if (instance == null) {
                instance = constructor.get();
                OBJECT_MAP.put(type, instance);
            }
            return type.cast(instance);
        }
    }
}
