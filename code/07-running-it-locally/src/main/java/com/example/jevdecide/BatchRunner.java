package com.example.jevdecide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs many decisions on a small, bounded thread pool and returns one row per item,
 * in the order the items were given.
 *
 * Worker threads receive only plain values (state, questions, options). They never
 * touch the Neo4j transaction. A failing row doesn't stop the others: every row carries
 * its own error_message, exactly as jev.decide does.
 *
 * The "concurrency" option belongs to the batch, so it's removed from the options map
 * before the rest goes to JevClient (which rejects keys it doesn't know).
 */
public class BatchRunner {

    public static final int DEFAULT_CONCURRENCY = 4;
    public static final int MAX_CONCURRENCY = 16;
    public static final int MAX_ITEMS = 10_000;

    static final String INTERRUPTED = "interrupted: the batch was interrupted before this row finished";

    /** One output row. The id is whatever the caller supplied, or the item's position. */
    public record Row(Object id, Map<String, Object> answers, String errorMessage, Map<String, Object> metadata) {}

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private final JevClient client;

    public BatchRunner(JevClient client) {
        this.client = client;
    }

    public List<Row> run(List<Map<String, Object>> items,
                         Map<String, Object> questions,
                         Map<String, Object> optionValues) {
        if (items == null) {
            throw new IllegalArgumentException("items must be a list of maps, each with an id and a state");
        }
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a batch holds at most " + MAX_ITEMS
                    + " items; split larger sets into several calls");
        }
        if (items.isEmpty()) {
            return List.of();
        }

        Map<String, Object> clientOptions = optionValues == null ? new HashMap<>() : new HashMap<>(optionValues);
        Object requested = clientOptions.remove("concurrency");

        int concurrency;
        try {
            concurrency = parseConcurrency(requested);
        } catch (IllegalArgumentException e) {
            return everyRow(items, "validation: " + e.getMessage());
        }

        int threads = Math.min(concurrency, items.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "jev-decide-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<Row>> futures = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                final int index = i;
                futures.add(pool.submit(() -> runOne(index, items.get(index), questions, clientOptions)));
            }

            List<Row> rows = new ArrayList<>(items.size());
            boolean interrupted = false;
            for (int i = 0; i < items.size(); i++) {
                Object id = idOf(items.get(i), i);
                if (interrupted) {
                    rows.add(errorRow(id, INTERRUPTED));
                    continue;
                }
                try {
                    rows.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    interrupted = true;
                    pool.shutdownNow();
                    Thread.currentThread().interrupt();
                    rows.add(errorRow(id, INTERRUPTED));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    rows.add(errorRow(id, "internal: " + cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                }
            }
            return rows;
        } finally {
            pool.shutdownNow();
        }
    }

    private Row runOne(int index, Map<String, Object> item, Map<String, Object> questions,
                       Map<String, Object> options) {
        Object id = idOf(item, index);
        if (item == null || !item.containsKey("state")) {
            return errorRow(id, "validation: item " + index + " needs a state");
        }
        Map<String, Object> result = client.decide(item.get("state"), questions, options);
        return new Row(id, asMap(result.get("answers")), (String) result.get("error_message"),
                asMap(result.get("metadata")));
    }

    private static Object idOf(Map<String, Object> item, int index) {
        if (item != null && item.containsKey("id")) {
            return item.get("id");
        }
        return (long) index;
    }

    private static int parseConcurrency(Object value) {
        if (value == null) {
            return DEFAULT_CONCURRENCY;
        }
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new IllegalArgumentException("concurrency must be a whole number");
        }
        long result = n.longValue();
        if (result < 1 || result > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("concurrency must be between 1 and " + MAX_CONCURRENCY);
        }
        return (int) result;
    }

    private static List<Row> everyRow(List<Map<String, Object>> items, String message) {
        List<Row> rows = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            rows.add(errorRow(idOf(items.get(i), i), message));
        }
        return rows;
    }

    private static Row errorRow(Object id, String message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", null);
        metadata.put("latency_ms", 0L);
        metadata.put("attempts", 0L);
        metadata.put("authenticated", false);
        return new Row(id, null, message, metadata);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
