package com.example.jevdecide;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Moves values between the two type systems this function sits between.
 *
 * toJson checks what Cypher handed us and rejects anything JSON can't carry.
 * fromJson turns what Jackson parsed into types Neo4j accepts as return values:
 * Long, Double, Boolean, String, List and Map. Jackson produces Integer for small
 * numbers, and Neo4j doesn't take those, so they're widened to Long here.
 */
public final class TypeNormalizer {

    private TypeNormalizer() {}

    public static Object toJson(Object value, boolean sortKeys) {
        return toJson(value, sortKeys, "$");
    }

    private static Object toJson(Object value, boolean sortKeys, String path) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (value instanceof Double || value instanceof Float) {
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException(path + ": NaN and infinity can't be sent as JSON");
                }
            }
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = sortKeys ? new TreeMap<>() : new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException(path + ": map keys must be text");
                }
                out.put(key, toJson(entry.getValue(), sortKeys, path + "." + key));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            int index = 0;
            for (Object item : collection) {
                out.add(toJson(item, sortKeys, path + "[" + index + "]"));
                index++;
            }
            return out;
        }
        throw new IllegalArgumentException(path + ": unsupported type " + value.getClass().getSimpleName()
                + "; use text, numbers, booleans, maps or lists");
    }

    public static Object fromJson(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Long || value instanceof Double) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof BigInteger big) {
            if (big.bitLength() < 64) {
                return big.longValue();
            }
            return big.doubleValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), fromJson(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            for (Object item : collection) {
                out.add(fromJson(item));
            }
            return out;
        }
        return String.valueOf(value);
    }
}
