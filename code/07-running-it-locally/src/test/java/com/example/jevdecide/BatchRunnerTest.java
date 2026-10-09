package com.example.jevdecide;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRunnerTest {

    private static final String ANSWER = """
            {"answers": {"d": {"choice": "%s", "confidence": 0.5,
                               "probabilities": {"A": 0.75, "B": 0.25}}}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    private BatchRunner runner(HttpTransport transport) {
        return new BatchRunner(new JevClient(transport, mapper, () -> "test-key", millis -> { }));
    }

    private static Map<String, Object> questions() {
        return Map.of("d", Map.of(
                "type", "choice",
                "instructions", "Pick one",
                "criteria", Map.of("A", "first", "B", "second")));
    }

    private static List<Map<String, Object>> items(int count) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Map.of("id", "row" + i, "state", "s" + i));
        }
        return list;
    }

    private String stateOf(String body) throws java.io.IOException {
        return mapper.readTree(body).get("state").asText();
    }

    @SuppressWarnings("unchecked")
    private static String choiceOf(BatchRunner.Row row) {
        return (String) ((Map<String, Object>) row.answers().get("d")).get("choice");
    }

    @Test
    void rowsComeBackInInputOrderWithTheirIds() {
        // Earlier items sleep longer, so they finish last. The output must still be in input order.
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            String state = stateOf(body);
            Thread.sleep(40 - Integer.parseInt(state.substring(1)));
            return new HttpTransport.Response(200, ANSWER.formatted(state));
        };

        List<BatchRunner.Row> rows = runner(transport).run(items(20), questions(), Map.of("concurrency", 8L));

        assertEquals(20, rows.size());
        for (int i = 0; i < 20; i++) {
            assertEquals("row" + i, rows.get(i).id());
            assertNull(rows.get(i).errorMessage());
            assertEquals("s" + i, choiceOf(rows.get(i)));
        }
    }

    @Test
    void concurrencyIsBoundedByTheOption() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } finally {
                running.decrementAndGet();
            }
            return new HttpTransport.Response(200, ANSWER.formatted("A"));
        };

        List<BatchRunner.Row> rows = runner(transport).run(items(12), questions(), Map.of("concurrency", 3L));

        assertEquals(12, rows.size());
        assertTrue(peak.get() <= 3, "peak was " + peak.get());
        assertTrue(peak.get() >= 2, "calls never overlapped, peak was " + peak.get());
    }

    @Test
    void defaultConcurrencyIsFour() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(50);
            } finally {
                running.decrementAndGet();
            }
            return new HttpTransport.Response(200, ANSWER.formatted("A"));
        };

        runner(transport).run(items(12), questions(), Map.of());

        assertTrue(peak.get() <= BatchRunner.DEFAULT_CONCURRENCY, "peak was " + peak.get());
    }

    @Test
    void oneFailingRowDoesNotStopTheOthers() {
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            String state = stateOf(body);
            if (state.equals("s3")) {
                return new HttpTransport.Response(422, "bad criteria");
            }
            return new HttpTransport.Response(200, ANSWER.formatted(state));
        };

        List<BatchRunner.Row> rows = runner(transport).run(items(6), questions(), Map.of());

        assertEquals(6, rows.size());
        assertTrue(rows.get(3).errorMessage().startsWith("http_4xx: 422"));
        assertNull(rows.get(3).answers());
        for (int i : new int[] {0, 1, 2, 4, 5}) {
            assertNull(rows.get(i).errorMessage());
        }
    }

    @Test
    void itemsWithoutAStateOrNullItemsBecomeRowErrors() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Map.of("id", 1L));
        list.add(null);
        list.add(Map.of("id", 3L, "state", "ok"));
        HttpTransport transport = (uri, headers, body, connect, request) ->
                new HttpTransport.Response(200, ANSWER.formatted("A"));

        List<BatchRunner.Row> rows = runner(transport).run(list, questions(), Map.of());

        assertTrue(rows.get(0).errorMessage().startsWith("validation:"));
        assertEquals(1L, rows.get(0).id());
        assertTrue(rows.get(1).errorMessage().startsWith("validation:"));
        assertEquals(1L, rows.get(1).id());
        assertNull(rows.get(2).errorMessage());
        assertEquals(3L, rows.get(2).id());
    }

    @Test
    void aMissingIdFallsBackToThePosition() {
        List<Map<String, Object>> list = List.of(Map.of("state", "x"), Map.of("state", "y"));
        HttpTransport transport = (uri, headers, body, connect, request) ->
                new HttpTransport.Response(200, ANSWER.formatted("A"));

        List<BatchRunner.Row> rows = runner(transport).run(list, questions(), Map.of());

        assertEquals(0L, rows.get(0).id());
        assertEquals(1L, rows.get(1).id());
    }

    @Test
    void invalidConcurrencyGivesEveryRowAValidationErrorAndMakesNoCalls() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            calls.incrementAndGet();
            return new HttpTransport.Response(200, ANSWER.formatted("A"));
        };

        for (Object bad : new Object[] {0L, 17L, "two", 2.5}) {
            List<BatchRunner.Row> rows = runner(transport).run(items(3), questions(), Map.of("concurrency", bad));
            assertEquals(3, rows.size());
            for (BatchRunner.Row row : rows) {
                assertTrue(row.errorMessage().startsWith("validation:"));
                assertTrue(row.errorMessage().contains("concurrency"));
            }
        }
        assertEquals(0, calls.get());
    }

    @Test
    void concurrencyIsNotPassedOnAndOtherOptionsStillApply() {
        AtomicReference<String> lastBody = new AtomicReference<>();
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            lastBody.set(body);
            return new HttpTransport.Response(200, ANSWER.formatted("A"));
        };

        List<BatchRunner.Row> rows = runner(transport).run(items(2), questions(),
                Map.of("concurrency", 2L, "model", "tev1:0.8b"));

        assertNull(rows.get(0).errorMessage());
        assertTrue(lastBody.get().contains("\"model\":\"tev1:0.8b\""));
    }

    @Test
    void badClientOptionsAreReportedOnEveryRow() {
        HttpTransport transport = (uri, headers, body, connect, request) ->
                new HttpTransport.Response(200, ANSWER.formatted("A"));

        List<BatchRunner.Row> rows = runner(transport).run(items(3), questions(), Map.of("max_retries", 99L));

        for (BatchRunner.Row row : rows) {
            assertTrue(row.errorMessage().startsWith("validation:"));
        }
    }

    @Test
    void emptyItemsGiveNoRowsAndNullItemsAreRejected() {
        HttpTransport transport = (uri, headers, body, connect, request) ->
                new HttpTransport.Response(200, ANSWER.formatted("A"));

        assertTrue(runner(transport).run(List.of(), questions(), Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runner(transport).run(null, questions(), Map.of()));
    }

    @Test
    void tooManyItemsAreRejected() {
        HttpTransport transport = (uri, headers, body, connect, request) ->
                new HttpTransport.Response(200, ANSWER.formatted("A"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> runner(transport).run(items(BatchRunner.MAX_ITEMS + 1), questions(), Map.of()));
        assertTrue(e.getMessage().contains("10000"));
    }

    @Test
    void anInterruptedBatchReturnsInterruptedRowsAndKeepsTheFlag() {
        CountDownLatch never = new CountDownLatch(1);
        HttpTransport transport = (uri, headers, body, connect, request) -> {
            never.await();
            return new HttpTransport.Response(200, ANSWER.formatted("A"));
        };

        Thread.currentThread().interrupt();
        List<BatchRunner.Row> rows;
        try {
            rows = runner(transport).run(items(4), questions(), Map.of("concurrency", 2L));
        } finally {
            // Reading the flag also clears it, so later tests aren't affected.
            assertTrue(Thread.interrupted(), "the interrupt flag should be restored");
        }

        assertEquals(4, rows.size());
        for (BatchRunner.Row row : rows) {
            assertTrue(row.errorMessage().startsWith("interrupted:"));
        }
    }
}
