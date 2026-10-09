package com.example.jevdecide;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeNormalizerTest {

    @Test
    void fromJsonWidensIntegersToLong() {
        assertInstanceOf(Long.class, TypeNormalizer.fromJson(7));
        assertEquals(7L, TypeNormalizer.fromJson(7));
        assertInstanceOf(Long.class, TypeNormalizer.fromJson((short) 3));
        assertInstanceOf(Long.class, TypeNormalizer.fromJson((byte) 1));
    }

    @Test
    void fromJsonKeepsLongDoubleBooleanStringAndNull() {
        assertEquals(5L, TypeNormalizer.fromJson(5L));
        assertEquals(0.75, TypeNormalizer.fromJson(0.75));
        assertEquals(true, TypeNormalizer.fromJson(true));
        assertEquals("text", TypeNormalizer.fromJson("text"));
        assertNull(TypeNormalizer.fromJson(null));
    }

    @Test
    void fromJsonHandlesFloatAndBigNumbers() {
        assertInstanceOf(Double.class, TypeNormalizer.fromJson(1.5f));
        assertEquals(12L, TypeNormalizer.fromJson(BigInteger.valueOf(12)));
        assertInstanceOf(Double.class, TypeNormalizer.fromJson(new BigInteger("99999999999999999999")));
        assertEquals(2.5, TypeNormalizer.fromJson(new BigDecimal("2.5")));
    }

    @Test
    void fromJsonNormalizesNestedStructures() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("count", 3);
        inner.put("p", 0.5);
        List<Object> list = new ArrayList<>();
        list.add(1);
        list.add(inner);
        Map<String, Object> outer = new HashMap<>();
        outer.put("items", list);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) TypeNormalizer.fromJson(outer);
        @SuppressWarnings("unchecked")
        List<Object> items = (List<Object>) result.get("items");

        assertEquals(1L, items.get(0));
        @SuppressWarnings("unchecked")
        Map<String, Object> normalizedInner = (Map<String, Object>) items.get(1);
        assertInstanceOf(Long.class, normalizedInner.get("count"));
        assertInstanceOf(Double.class, normalizedInner.get("p"));
    }

    @Test
    void toJsonSortsMapKeysWhenAsked() {
        Map<String, Object> input = new HashMap<>();
        input.put("b", 1L);
        input.put("a", 2L);
        input.put("c", 3L);
        @SuppressWarnings("unchecked")
        Map<String, Object> sorted = (Map<String, Object>) TypeNormalizer.toJson(input, true);
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(sorted.keySet()));
    }

    @Test
    void toJsonAcceptsNestedMapsListsAndNulls() {
        Map<String, Object> input = new HashMap<>();
        input.put("list", List.of(1L, 2L));
        input.put("nested", Map.of("flag", true));
        input.put("missing", null);
        TypeNormalizer.toJson(input, false);
    }

    @Test
    void toJsonRejectsUnsupportedTypes() {
        Map<String, Object> input = new HashMap<>();
        input.put("thing", new Object());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TypeNormalizer.toJson(input, false));
        assertEquals(true, e.getMessage().contains("$.thing"));
    }

    @Test
    void toJsonRejectsNaNAndInfinity() {
        assertThrows(IllegalArgumentException.class, () -> TypeNormalizer.toJson(Double.NaN, false));
        assertThrows(IllegalArgumentException.class,
                () -> TypeNormalizer.toJson(List.of(Double.POSITIVE_INFINITY), false));
    }

    @Test
    void toJsonRejectsNonTextKeys() {
        Map<Object, Object> input = new HashMap<>();
        input.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> TypeNormalizer.toJson(input, false));
    }
}
