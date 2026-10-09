package com.example.jevdecide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevClientTest {

    private static final String OK_BODY = """
            {"answers": {
              "decision": {"choice": "Flag", "confidence": 0.5,
                           "probabilities": {"Flag": 0.75, "Pass": 0.25}},
              "self_rating": {"score": 1.25}
            }}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeTransport transport;
    private List<Long> sleeps;
    private String key;
    private JevClient client;

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        sleeps = new ArrayList<>();
        key = "test-key";
        client = new JevClient(transport, mapper, () -> key, sleeps::add);
    }

    private static Map<String, Object> questions() {
        Map<String, Object> decision = new HashMap<>();
        decision.put("type", "choice");
        decision.put("instructions", "Flag or pass?");
        decision.put("criteria", Map.of("Flag", "Fraud", "Pass", "Legitimate"));

        Map<String, Object> rating = new HashMap<>();
        rating.put("type", "score");
        rating.put("instructions", "How confident are you?");
        rating.put("criteria", List.of("Not confident", "Somewhat confident", "Very confident"));

        Map<String, Object> all = new HashMap<>();
        all.put("decision", decision);
        all.put("self_rating", rating);
        return all;
    }

    private static String error(Map<String, Object> result) {
        return (String) result.get("error_message");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadata(Map<String, Object> result) {
        return (Map<String, Object>) result.get("metadata");
    }

    @Test
    void successReturnsAnswersWithNeo4jFriendlyTypes() {
        transport.respond(200, OK_BODY);

        Map<String, Object> result = client.decide("a transaction", questions(), Map.of());

        assertNull(error(result));
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = (Map<String, Object>) answers.get("decision");
        assertEquals("Flag", decision.get("choice"));
        assertInstanceOf(Double.class, decision.get("confidence"));
        assertEquals(1L, metadata(result).get("attempts"));
        assertEquals("jev-latest", metadata(result).get("model"));
    }

    @Test
    void requestCarriesModelKeyUrlAndQuestions() throws Exception {
        transport.respond(200, OK_BODY);

        client.decide("a transaction", questions(), Map.of());

        FakeTransport.Request request = transport.requests.get(0);
        assertEquals(Options.DEFAULT_URL, request.uri().toString());
        assertEquals("Bearer test-key", request.headers().get("Authorization"));
        JsonNode body = mapper.readTree(request.body());
        assertEquals("jev-latest", body.get("model").asText());
        assertEquals("a transaction", body.get("state").asText());
        assertTrue(body.get("questions").has("decision"));
        assertTrue(body.get("questions").get("self_rating").get("criteria").isArray());
        assertTrue(body.get("questions").get("decision").get("criteria").isObject());
    }

    @Test
    void mapStateIsSerializedWithSortedKeys() throws Exception {
        transport.respond(200, OK_BODY);
        Map<String, Object> state = new HashMap<>();
        state.put("b", 1L);
        state.put("a", 2L);

        client.decide(state, questions(), Map.of());

        JsonNode body = mapper.readTree(transport.requests.get(0).body());
        assertEquals("{\"a\":2,\"b\":1}", body.get("state").asText());
    }

    @Test
    void optionsOverrideModelAndUrl() {
        transport.respond(200, OK_BODY);
        Map<String, Object> options = new HashMap<>();
        options.put("model", "jev-1.13.0");
        options.put("url", "http://127.0.0.1:8080/v1/systemone");

        Map<String, Object> result = client.decide("x", questions(), options);

        assertNull(error(result));
        assertEquals("http://127.0.0.1:8080/v1/systemone", transport.requests.get(0).uri().toString());
        assertEquals("jev-1.13.0", metadata(result).get("model"));
    }

    @Test
    void rateLimitThenSuccessRetriesOnce() {
        transport.respond(429, "slow down").respond(200, OK_BODY);

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertNull(error(result));
        assertEquals(2L, metadata(result).get("attempts"));
        assertEquals(List.of(500L), sleeps);
    }

    @Test
    void persistentServerErrorStopsAfterRetriesWithBackoff() {
        transport.respond(503, "unavailable");

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("http_5xx:"));
        assertTrue(error(result).contains("after 3 attempts"));
        assertEquals(3, transport.requests.size());
        assertEquals(List.of(500L, 1000L), sleeps);
        assertNull(result.get("answers"));
    }

    @Test
    void clientErrorIsNotRetried() {
        transport.respond(422, "bad criteria shape");

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("http_4xx: 422"));
        assertEquals(1, transport.requests.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void longErrorBodiesAreTruncated() {
        transport.respond(400, "x".repeat(1000));

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).length() < 400);
        assertTrue(error(result).endsWith("..."));
    }

    @Test
    void timeoutsAreRetriedThenReported() {
        transport.fail(new HttpTimeoutException("timed out"));

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("timeout:"));
        assertEquals(3, transport.requests.size());
    }

    @Test
    void otherIoErrorsAreRetriedThenReported() {
        transport.fail(new IOException("connection reset"));

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("io:"));
        assertTrue(error(result).contains("connection reset"));
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        transport.respond(500, "boom");

        Map<String, Object> result = client.decide("x", questions(), Map.of("max_retries", 0L));

        assertEquals(1, transport.requests.size());
        assertTrue(error(result).endsWith("(after 1 attempt)"), error(result));
    }

    @Test
    void nonJsonSuccessBodyIsMalformedResponse() {
        transport.respond(200, "this is not json");

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("malformed_response:"));
    }

    @Test
    void successBodyWithoutAnswersIsMalformedResponse() {
        transport.respond(200, "{\"something\": 1}");

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("malformed_response:"));
    }

    @Test
    void missingKeyMakesNoCall() {
        key = null;

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(error(result).startsWith("no_api_key:"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void invalidQuestionsMakeNoCall() {
        Map<String, Object> result = client.decide("x", Map.of(), Map.of());

        assertTrue(error(result).startsWith("validation:"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void invalidOptionsMakeNoCall() {
        Map<String, Object> result = client.decide("x", questions(), Map.of("max_retries", 99L));

        assertTrue(error(result).startsWith("validation:"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void nullAndBlankStateAreRejected() {
        assertTrue(error(client.decide(null, questions(), Map.of())).startsWith("validation:"));
        assertTrue(error(client.decide("  ", questions(), Map.of())).startsWith("validation:"));
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    void unsupportedStateTypeIsRejected() {
        Map<String, Object> state = new HashMap<>();
        state.put("thing", new Object());

        Map<String, Object> result = client.decide(state, questions(), Map.of());

        assertTrue(error(result).startsWith("validation:"));
        assertTrue(transport.requests.isEmpty());
    }

    private static final String LOCAL = "http://localhost:11434/v1/systemone";

    @Test
    void defaultEndpointIsAuthenticated() {
        transport.respond(200, OK_BODY);

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertEquals(Boolean.TRUE, metadata(result).get("authenticated"));
    }

    @Test
    void localEndpointNeedsNoKeyAndGetsNoAuthorizationHeader() {
        key = null;
        transport.respond(200, OK_BODY);

        Map<String, Object> result = client.decide("x", questions(), Map.of("url", LOCAL));

        assertNull(error(result));
        assertEquals(LOCAL, transport.requests.get(0).uri().toString());
        assertTrue(!transport.requests.get(0).headers().containsKey("Authorization"));
        assertEquals(Boolean.FALSE, metadata(result).get("authenticated"));
    }

    @Test
    void keyIsNotSentToAnotherHostEvenWhenOneIsConfigured() {
        transport.respond(200, OK_BODY);

        client.decide("x", questions(), Map.of("url", "https://example.com/v1/systemone"));

        assertTrue(!transport.requests.get(0).headers().containsKey("Authorization"));
        assertTrue(!transport.requests.get(0).headers().toString().contains("test-key"));
    }

    @Test
    void lookalikeUrlDoesNotReceiveTheKey() {
        transport.respond(200, OK_BODY);

        client.decide("x", questions(), Map.of("url", "https://api.typesafe.ai@evil.com/v1/systemone"));

        assertEquals("evil.com", transport.requests.get(0).uri().getHost());
        assertTrue(!transport.requests.get(0).headers().containsKey("Authorization"));
    }

    @Test
    void keySupplierIsNotEvenCalledForNonJevEndpoints() {
        JevClient strict = new JevClient(transport, mapper,
                () -> { throw new AssertionError("the key must not be read"); }, sleeps::add);
        transport.respond(200, OK_BODY);

        Map<String, Object> result = strict.decide("x", questions(), Map.of("url", LOCAL));

        assertNull(error(result));
    }

    @Test
    void connectionRefusedNamesTheTarget() {
        transport.fail(new ConnectException());

        Map<String, Object> result = client.decide("x", questions(), Map.of("url", LOCAL));

        assertTrue(error(result).startsWith("io: ConnectException reaching localhost:11434"));
        assertTrue(error(result).contains("after 3 attempts"));
    }

    @Test
    void localModelResponseWithExtraFieldsIsAccepted() {
        key = null;
        transport.respond(200, """
                {"model":"tev1:0.8b","answers":{
                  "decision":{"type":"choice","choice":"Pass",
                    "probabilities":{"Flag":0.2,"Pass":0.8},"confidence":0.27},
                  "self_rating":{"type":"score","score":1,
                    "legend":{"0":"Low","1":"Mid","2":"High"},
                    "probabilities":{"0":0.29,"1":0.42,"2":0.29},"confidence":0.015}},
                 "usage":{"input_tokens":331,"output_tokens":0},"prompt_eval_cached_count":90}
                """);

        Map<String, Object> result = client.decide("x", questions(), Map.of("url", LOCAL, "model", "tev1:0.8b"));

        assertNull(error(result));
        @SuppressWarnings("unchecked")
        Map<String, Object> answers = (Map<String, Object>) result.get("answers");
        @SuppressWarnings("unchecked")
        Map<String, Object> rating = (Map<String, Object>) answers.get("self_rating");
        assertEquals(1L, rating.get("score"));
        assertEquals("tev1:0.8b", metadata(result).get("model"));
        assertTrue(!result.containsKey("usage"));
    }

    @Test
    void theApiKeyNeverAppearsInResults() {
        transport.respond(500, "server said no");

        Map<String, Object> result = client.decide("x", questions(), Map.of());

        assertTrue(!result.toString().contains("test-key"));
        assertNotNull(metadata(result));
    }
}
