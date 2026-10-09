package com.example.jevdecide;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings read from the optional third argument of jev.decide.
 * Unknown keys are rejected so a typo doesn't silently fall back to a default.
 */
public record Options(String model,
                      Duration connectTimeout,
                      Duration requestTimeout,
                      int maxRetries) {

    public static final String DEFAULT_MODEL = "jev-latest";
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_MAX_RETRIES = 2;

    private static final Set<String> KNOWN_KEYS = Set.of(
            "model", "connect_timeout_ms", "request_timeout_ms", "max_retries");

    public static Options defaults() {
        return new Options(
                DEFAULT_MODEL,
                Duration.ofMillis(DEFAULT_CONNECT_TIMEOUT_MS),
                Duration.ofMillis(DEFAULT_REQUEST_TIMEOUT_MS),
                DEFAULT_MAX_RETRIES);
    }

    public static Options from(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return defaults();
        }

        Set<String> unknown = new TreeSet<>(values.keySet());
        unknown.removeAll(KNOWN_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown option(s): " + String.join(", ", unknown)
                    + "; known options are " + new TreeSet<>(KNOWN_KEYS));
        }

        String model = text(values, "model", DEFAULT_MODEL);
        long connect = whole(values, "connect_timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS, 1, 60_000);
        long request = whole(values, "request_timeout_ms", DEFAULT_REQUEST_TIMEOUT_MS, 1, 120_000);
        long retries = whole(values, "max_retries", DEFAULT_MAX_RETRIES, 0, 5);

        return new Options(model, Duration.ofMillis(connect), Duration.ofMillis(request), (int) retries);
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + " must be non-blank text");
        }
        return s;
    }

    private static long whole(Map<String, Object> values, String key, long fallback, long min, long max) {
        Object value = values.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
        long result = n.longValue();
        if (result < min || result > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return result;
    }
}
