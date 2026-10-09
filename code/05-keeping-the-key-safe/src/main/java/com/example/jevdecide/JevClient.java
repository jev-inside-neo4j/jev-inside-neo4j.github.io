package com.example.jevdecide;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the request, sends it with retries and turns the outcome into a result map.
 * The decide method never throws: every failure comes back as error_message, so one
 * bad row can't abort a query that processes many.
 *
 * error_message always starts with one of these prefixes:
 * validation, no_api_key, timeout, io, http_429, http_5xx, http_4xx,
 * malformed_response, interrupted, internal.
 *
 * The API key is only read, and the Authorization header only sent, when the endpoint
 * is https://api.typesafe.ai (see Options.sendsApiKey). metadata.authenticated says
 * whether the key went out.
 */
public class JevClient {

    /** Pauses between retries. Tests replace it so they don't actually wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final long BACKOFF_MS = 500;
    static final int MAX_BODY_IN_ERROR = 300;

    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final Supplier<String> apiKey;
    private final Sleeper sleeper;

    public JevClient(HttpTransport transport, ObjectMapper mapper, Supplier<String> apiKey, Sleeper sleeper) {
        this.transport = transport;
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.sleeper = sleeper;
    }

    public Map<String, Object> decide(Object state, Map<String, Object> questions, Map<String, Object> optionValues) {
        long started = System.nanoTime();

        Options options;
        try {
            options = Options.from(optionValues);
        } catch (IllegalArgumentException e) {
            return failure("validation: " + e.getMessage(), null, started, 0, false);
        }

        try {
            return run(state, questions, options, started);
        } catch (RuntimeException e) {
            return failure("internal: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    options.model(), started, 0, false);
        }
    }

    private Map<String, Object> run(Object state, Map<String, Object> questions, Options options, long started) {
        String model = options.model();

        List<String> problems = QuestionValidator.validate(questions);
        if (!problems.isEmpty()) {
            return failure("validation: " + String.join("; ", problems), model, started, 0, false);
        }

        String body;
        try {
            body = mapper.writeValueAsString(requestBody(state, questions, model));
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return failure("validation: " + e.getMessage(), model, started, 0, false);
        }

        // The key is read, and sent, only for the real Jev endpoint.
        boolean authenticated = options.sendsApiKey();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (authenticated) {
            String key = apiKey.get();
            if (key == null || key.isBlank()) {
                return failure("no_api_key: set TYPESAFE_API_KEY as an environment variable or JVM property",
                        model, started, 0, false);
            }
            headers.put("Authorization", "Bearer " + key);
        }

        String lastError = "no attempt made";
        int attempts = 0;

        for (int attempt = 0; attempt <= options.maxRetries(); attempt++) {
            if (attempt > 0) {
                try {
                    sleeper.sleep(BACKOFF_MS << (attempt - 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return failure("interrupted: the call was interrupted while waiting to retry",
                            model, started, attempts, authenticated);
                }
            }
            attempts++;

            try {
                HttpTransport.Response response = transport.post(
                        options.url(), headers, body, options.connectTimeout(), options.requestTimeout());

                if (response.status() == 200) {
                    return parseSuccess(response.body(), model, started, attempts, authenticated);
                }
                if (response.status() == 429) {
                    lastError = "http_429: rate limited";
                } else if (response.status() >= 500) {
                    lastError = "http_5xx: " + response.status() + " " + truncate(response.body());
                } else {
                    return failure("http_4xx: " + response.status() + " " + truncate(response.body()),
                            model, started, attempts, authenticated);
                }
            } catch (HttpTimeoutException e) {
                lastError = "timeout: no response from " + options.target() + " within the configured limits";
            } catch (IOException e) {
                String detail = e.getMessage() == null ? "" : ": " + e.getMessage();
                lastError = "io: " + e.getClass().getSimpleName() + " reaching " + options.target() + detail;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return failure("interrupted: the call was interrupted", model, started, attempts, authenticated);
            }
        }

        String count = attempts == 1 ? "1 attempt" : attempts + " attempts";
        return failure(lastError + " (after " + count + ")", model, started, attempts, authenticated);
    }

    private Map<String, Object> requestBody(Object state, Map<String, Object> questions, String model)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("state", stateText(state));
        body.put("questions", TypeNormalizer.toJson(questions, false));
        return body;
    }

    /**
     * The API takes the state as text. Text goes through unchanged. A map or list is
     * serialized with sorted keys, so the same input always produces the same request.
     */
    private String stateText(Object state) throws JsonProcessingException {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (state instanceof String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException("state must not be blank");
            }
            return text;
        }
        return mapper.writeValueAsString(TypeNormalizer.toJson(state, true));
    }

    private Map<String, Object> parseSuccess(String responseBody, String model, long started, int attempts,
                                             boolean authenticated) {
        Map<String, Object> root;
        try {
            root = mapper.readValue(responseBody, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return failure("malformed_response: the response body is not a JSON object",
                    model, started, attempts, authenticated);
        }

        Object answers = root == null ? null : root.get("answers");
        if (!(answers instanceof Map)) {
            return failure("malformed_response: the response has no answers object",
                    model, started, attempts, authenticated);
        }
        return result(TypeNormalizer.fromJson(answers), null, model, started, attempts, authenticated);
    }

    private static Map<String, Object> failure(String message, String model, long started, int attempts,
                                               boolean authenticated) {
        return result(null, message, model, started, attempts, authenticated);
    }

    private static Map<String, Object> result(Object answers, String error, String model, long started,
                                              int attempts, boolean authenticated) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", model);
        metadata.put("latency_ms", (System.nanoTime() - started) / 1_000_000L);
        metadata.put("attempts", (long) attempts);
        metadata.put("authenticated", authenticated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answers", answers);
        out.put("error_message", error);
        out.put("metadata", metadata);
        return out;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_BODY_IN_ERROR ? text : text.substring(0, MAX_BODY_IN_ERROR) + "...";
    }
}
