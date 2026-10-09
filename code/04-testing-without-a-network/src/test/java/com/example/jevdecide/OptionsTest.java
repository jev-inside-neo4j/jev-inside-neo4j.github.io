package com.example.jevdecide;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionsTest {

    @Test
    void nullAndEmptyGiveDefaults() {
        for (Options o : new Options[] {Options.from(null), Options.from(Map.of())}) {
            assertEquals(Options.DEFAULT_MODEL, o.model());
            assertEquals(Duration.ofMillis(5_000), o.connectTimeout());
            assertEquals(Duration.ofMillis(15_000), o.requestTimeout());
            assertEquals(2, o.maxRetries());
        }
    }

    @Test
    void overridesAreApplied() {
        Map<String, Object> values = new HashMap<>();
        values.put("model", "jev-1.13.0");
        values.put("connect_timeout_ms", 1000L);
        values.put("request_timeout_ms", 2000);
        values.put("max_retries", 0L);

        Options o = Options.from(values);
        assertEquals("jev-1.13.0", o.model());
        assertEquals(Duration.ofMillis(1000), o.connectTimeout());
        assertEquals(Duration.ofMillis(2000), o.requestTimeout());
        assertEquals(0, o.maxRetries());
    }

    @Test
    void unknownKeysAreRejectedAndNamed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Options.from(Map.of("modle", "x")));
        assertTrue(e.getMessage().contains("modle"));
    }

    @Test
    void outOfRangeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("max_retries", 6L)));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("max_retries", -1L)));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("connect_timeout_ms", 0L)));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("request_timeout_ms", 120_001L)));
    }

    @Test
    void wrongTypesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("max_retries", "two")));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("max_retries", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("model", 5L)));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("model", " ")));
    }
}
