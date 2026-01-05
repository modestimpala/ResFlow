package moddy.resflow.util;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Utility class for reflection operations.
 * Helps access private fields and methods in game code.
 * <a href="https://github.com/4rg0n/songs-of-syx-mod-example/blob/master/doc/howto/access_game_code.md">Credit</a>
 */
public class ReflectionUtil {

    private ReflectionUtil() {
        // Utility class - no instantiation
    }

    /**
     * Get a declared field from an object's class
     */
    public static Optional<Field> getDeclaredField(String fieldName, Object instance) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            return Optional.of(field);
        } catch (NoSuchFieldException e) {
            return Optional.empty();
        }
    }

    /**
     * Get a declared field from a specific class
     */
    public static Optional<Field> getDeclaredField(String fieldName, Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            return Optional.of(field);
        } catch (NoSuchFieldException e) {
            return Optional.empty();
        }
    }

    /**
     * Get the value of a declared field
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getDeclaredFieldValue(Field field, Object instance) {
        field.setAccessible(true);

        try {
            return Optional.ofNullable((T) field.get(instance));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Get the value of a declared field by name
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getDeclaredFieldValue(String fieldName, Object instance) {
        return getDeclaredField(fieldName, instance).flatMap(field ->
            getDeclaredFieldValue(field, instance)
        );
    }
}
