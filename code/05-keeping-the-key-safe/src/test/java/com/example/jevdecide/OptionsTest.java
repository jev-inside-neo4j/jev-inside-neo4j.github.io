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
            assertEquals(Options.DEFAULT_URL, o.url().toString());
            assertEquals(Duration.ofMillis(5_000), o.connectTimeout());
            assertEquals(Duration.ofMillis(15_000), o.requestTimeout());
            assertEquals(2, o.maxRetries());
        }
    }

    @Test
    void overridesAreApplied() {
        Map<String, Object> values = new HashMap<>();
        values.put("model", "jev-1.13.0");
        values.put("url", "http://127.0.0.1:8080/v1/systemone");
        values.put("connect_timeout_ms", 1000L);
        values.put("request_timeout_ms", 2000);
        values.put("max_retries", 0L);

        Options o = Options.from(values);
        assertEquals("jev-1.13.0", o.model());
        assertEquals("127.0.0.1", o.url().getHost());
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

    private static boolean sends(String url) {
        return Options.from(Map.of("url", url)).sendsApiKey();
    }

    @Test
    void keyIsSentOnlyToHttpsApiTypesafeAi() {
        assertTrue(Options.defaults().sendsApiKey());
        assertTrue(sends("https://api.typesafe.ai/v1/systemone"));
        assertTrue(sends("https://API.TypeSafe.AI/v1/systemone"));
        assertTrue(sends("https://api.typesafe.ai:8443/v1/systemone"));
    }

    @Test
    void keyIsNeverSentToOtherHostsOrCleartext() {
        assertEquals(false, sends("http://localhost:11434/v1/systemone"));
        assertEquals(false, sends("http://127.0.0.1:8080/v1/systemone"));
        assertEquals(false, sends("https://example.com/v1/systemone"));
        assertEquals(false, sends("http://api.typesafe.ai/v1/systemone"));
    }

    @Test
    void lookalikeHostsDoNotReceiveTheKey() {
        // userinfo trick: the real host is evil.com
        assertEquals(false, sends("https://api.typesafe.ai@evil.com/v1/systemone"));
        // a different host that merely starts with the right name
        assertEquals(false, sends("https://api.typesafe.ai.evil.com/v1/systemone"));
        assertEquals(false, sends("https://evil-api.typesafe.ai/v1/systemone"));
        assertEquals(false, sends("https://evil.com/api.typesafe.ai"));
    }

    @Test
    void targetShowsHostAndPortOnly() {
        assertEquals("localhost:11434",
                Options.from(Map.of("url", "http://localhost:11434/v1/systemone")).target());
        assertEquals("api.typesafe.ai", Options.defaults().target());
        assertEquals("evil.com",
                Options.from(Map.of("url", "https://user:secret@evil.com/x")).target());
    }

    @Test
    void badUrlsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("url", "not a url")));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("url", "ftp://example.com/x")));
        assertThrows(IllegalArgumentException.class, () -> Options.from(Map.of("url", "/relative/path")));
    }
}
