package com.example.jevdecide;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Builds the request, sends it once and turns the response into a result map.
 * This first version keeps to the happy path: one attempt, no validation, and a
 * failure raises an error. Chapter 3 changes that.
 */
public class JevClient {

    static final String DEFAULT_MODEL = "jev-latest";
    static final URI ENDPOINT = URI.create("https://api.typesafe.ai/v1/systemone");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpTransport transport;
    private final ObjectMapper mapper;
    private final Supplier<String> apiKey;

    public JevClient(HttpTransport transport, ObjectMapper mapper, Supplier<String> apiKey) {
        this.transport = transport;
        this.mapper = mapper;
        this.apiKey = apiKey;
    }

    public Map<String, Object> decide(Object state, Map<String, Object> questions, Map<String, Object> options) {
        long started = System.nanoTime();
        String model = modelFrom(options);

        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("TYPESAFE_API_KEY is not set");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + key);

        try {
            String body = mapper.writeValueAsString(requestBody(state, questions, model));
            HttpTransport.Response response =
                    transport.post(ENDPOINT, headers, body, CONNECT_TIMEOUT, REQUEST_TIMEOUT);

            if (response.status() != 200) {
                throw new IllegalStateException("Jev returned HTTP " + response.status() + ": " + response.body());
            }

            Map<String, Object> root =
                    mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
            Object answers = root == null ? null : root.get("answers");
            if (!(answers instanceof Map)) {
                throw new IllegalStateException("the response has no answers object");
            }
            return result(TypeNormalizer.fromJson(answers), model, started);
        } catch (IOException e) {
            throw new IllegalStateException("could not call Jev: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the call was interrupted", e);
        }
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
        if (state instanceof String text) {
            return text;
        }
        return mapper.writeValueAsString(TypeNormalizer.toJson(state, true));
    }

    private static String modelFrom(Map<String, Object> options) {
        Object model = options == null ? null : options.get("model");
        return model instanceof String text && !text.isBlank() ? text : DEFAULT_MODEL;
    }

    private static Map<String, Object> result(Object answers, String model, long started) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", model);
        metadata.put("latency_ms", (System.nanoTime() - started) / 1_000_000L);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answers", answers);
        out.put("error_message", null);
        out.put("metadata", metadata);
        return out;
    }
}
