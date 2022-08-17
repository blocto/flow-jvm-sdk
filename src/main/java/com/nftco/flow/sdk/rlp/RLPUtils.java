package com.nftco.flow.sdk.rlp;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class RLPUtils {
    static Map<Class, List<Field>> FIELDS = new HashMap<>();
    static Map<Class, List<Container>> CONTAINERS = new HashMap<>();
    static Map<Class, Constructor<?>> CONSTRUCTORS = new HashMap<>();
    static Map<Class, Object> STATIC_OBJECTS = new HashMap<>();

    static RLPEncoder getAnnotatedRLPEncoder(AnnotatedElement element) {
        if (!element.isAnnotationPresent(RLPEncoding.class)) {
            return null;
        }
        Class<? extends RLPEncoder> encoder = element.getAnnotation(RLPEncoding.class).value();
        if (encoder == RLPEncoder.None.class) {
            return null;
        }
        return getInstance(encoder);
    }

    static RLPDecoder getAnnotatedRLPDecoder(AnnotatedElement element) {
        if (!element.isAnnotationPresent(RLPDecoding.class)) {
            return null;
        }
        Class<? extends RLPDecoder> decoder = element.getAnnotation(RLPDecoding.class).value();
        if (decoder == RLPDecoder.None.class) {
            return null;
        }
        return getInstance(decoder);
    }

    static List<Field> getRLPFields(Class clazz) {
        List<Field> fields = FIELDS.get(clazz);
        if (fields != null) return fields;

        Stream<Field> declaredFields =
                Arrays.stream(clazz.getDeclaredFields())
                        .filter(f -> !Modifier.isStatic(f.getModifiers()));

        List<Field> notIgnored = declaredFields
                .filter(x -> {
                    if (x.isAnnotationPresent(RLPIgnored.class)) {
                        if (x.isAnnotationPresent(RLP.class))
                            throw new RuntimeException(x.getName() + " is both annotated with @com.nftco.flow.sdk.rlp.RLP and @com.nftco.flow.sdk.rlp.RLPIgnored");
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());
        List<Field> annotated = notIgnored.stream()
                .filter(x -> x.isAnnotationPresent(RLP.class))
                .sorted(Comparator.comparingInt(x -> x.getAnnotation(RLP.class).value()))
                .collect(Collectors.toList());
        if (annotated.size() == 0) {
            Map<Class, List<Field>> tmp = new HashMap<>(FIELDS);
            tmp.put(
                    clazz, notIgnored.stream().filter(x -> !Modifier.isTransient(x.getModifiers()))
                    .peek(x -> x.setAccessible(true))
                    .collect(Collectors.toList())
            );
            FIELDS = tmp;
            return notIgnored;
        }
        for (int i = 0; i < annotated.size(); i++) {
            if (annotated.get(i).getAnnotation(RLP.class).value() != i)
                throw new RuntimeException(String.format("field %s of class %s should have com.nftco.flow.sdk.rlp.RLP(%d)", annotated.get(i), clazz, i));
        }
        Map<Class, List<Field>> tmp = new HashMap<>(FIELDS);
        annotated.forEach(f -> f.setAccessible(true));
        tmp.put(clazz, annotated);
        FIELDS = tmp;
        return annotated;
    }

    static List<Container> getRLPContainers(Class clazz) {
        List<Container> containers = CONTAINERS.get(clazz);
        if (containers != null) return containers;
        Map<Class, List<Container>> copied = new HashMap<>(CONTAINERS);
        List<Container> ret = getRLPFields(clazz).stream()
                .map(Container::fromField)
                .collect(Collectors.toList());
        copied.put(clazz, ret);
        CONTAINERS = copied;
        return ret;
    }

    static Comparator getKeyOrdering(Field field) {
        if (!field.isAnnotationPresent(RLPEncoding.class)) {
            return null;
        }
        Class<? extends Comparator> clazz = field.getAnnotation(RLPEncoding.class).keyOrdering();
        if (clazz == RLPEncoding.None.class) return null;
        if (!Map.class.isAssignableFrom(field.getType()) && !Set.class.isAssignableFrom(field.getType()))
            throw new RuntimeException("@com.nftco.flow.sdk.rlp.RLPEncoding.keyOrdering() is used on Map or Set other than "
                    + field.getName() + " "
                    + field.getType().getName());
        return getInstance(clazz);
    }

    static boolean isContainer(Class clazz) {
        return Map.class.isAssignableFrom(clazz) || Collection.class.isAssignableFrom(clazz);
    }

    static <T> T getInstance(Class<T> clazz) {
        T t = (T) STATIC_OBJECTS.get(clazz);
        if (t != null) return t;
        t = newInstance(clazz);
        Map<Class, Object> copied = new HashMap<>(STATIC_OBJECTS);
        copied.put(clazz, t);
        STATIC_OBJECTS = copied;
        return t;
    }

    static <T> T newInstance(Class<T> clazz) {
        Constructor<?> constructor = CONSTRUCTORS.get(clazz);
        if (constructor != null) {
            try {
                return (T) constructor.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        try {
            return clazz.newInstance();
        } catch (Exception ignored) {

        }
        try {
            Constructor<T> con = clazz.getDeclaredConstructor();
            con.setAccessible(true);
            Map<Class, Constructor<?>> copied = new HashMap<>(CONSTRUCTORS);
            copied.put(clazz, con);
            CONSTRUCTORS = copied;
            return con.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(clazz + " should has an no-argument constructor");
        }
    }
}
