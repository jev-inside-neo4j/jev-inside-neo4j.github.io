package com.example.jevdecide;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Settings read from the optional third argument of jev.decide.
 * Unknown keys are rejected so a typo doesn't silently fall back to a default.
 */
public record Options(String model,
                      URI url,
                      Duration connectTimeout,
                      Duration requestTimeout,
                      int maxRetries) {

    public static final String DEFAULT_MODEL = "jev-latest";
    public static final String DEFAULT_URL = "https://api.typesafe.ai/v1/systemone";
    public static final long DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final long DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** The only host that ever receives the API key. */
    static final String KEY_HOST = "api.typesafe.ai";

    private static final Set<String> KNOWN_KEYS = Set.of(
            "model", "url", "connect_timeout_ms", "request_timeout_ms", "max_retries");

    public static Options defaults() {
        return new Options(
                DEFAULT_MODEL,
                URI.create(DEFAULT_URL),
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
        URI url = parseUrl(text(values, "url", DEFAULT_URL));
        long connect = whole(values, "connect_timeout_ms", DEFAULT_CONNECT_TIMEOUT_MS, 1, 60_000);
        long request = whole(values, "request_timeout_ms", DEFAULT_REQUEST_TIMEOUT_MS, 1, 120_000);
        long retries = whole(values, "max_retries", DEFAULT_MAX_RETRIES, 0, 5);

        return new Options(model, url, Duration.ofMillis(connect), Duration.ofMillis(request), (int) retries);
    }

    /**
     * True only for https to exactly api.typesafe.ai. The parsed host is compared, not the
     * raw text, so https://api.typesafe.ai@evil.com/ (host evil.com) and
     * https://api.typesafe.ai.evil.com/ (a different host) both return false.
     * Any other endpoint, such as a local server, is called without the key.
     */
    public boolean sendsApiKey() {
        return "https".equalsIgnoreCase(url.getScheme()) && KEY_HOST.equalsIgnoreCase(url.getHost());
    }

    /** host or host:port of the endpoint, for error messages. Never includes credentials. */
    public String target() {
        return url.getPort() < 0 ? url.getHost() : url.getHost() + ":" + url.getPort();
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

    private static URI parseUrl(String text) {
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url must be an http or https URL with a host");
        }
        return uri;
    }
}
