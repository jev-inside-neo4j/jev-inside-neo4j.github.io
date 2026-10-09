package com.example.jevdecide;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.util.Map;

/**
 * jev.decide(state, questions, options) - structured decisions from Jev, callable from Cypher.
 * A thin wrapper: all the logic lives in JevClient so it can be tested without Neo4j.
 */
public class JevDecide {

    private static final String KEY_NAME = "TYPESAFE_API_KEY";

    /** Shared with JevDecideAll. The client keeps no per-call state, so threads can share it. */
    static final JevClient CLIENT = new JevClient(
            new JdkHttpTransport(),
            new ObjectMapper(),
            JevDecide::resolveApiKey,
            Thread::sleep);

    /** Environment variable first, then a JVM property, as in the Part 3 UDF. */
    private static String resolveApiKey() {
        String key = System.getenv(KEY_NAME);
        if (key == null || key.isBlank()) {
            key = System.getProperty(KEY_NAME);
        }
        return key;
    }

    @UserFunction("jev.decide")
    @Description("Ask Jev typed questions about a state. Returns {answers, error_message, metadata}. "
            + "Failures are reported in error_message and never thrown.")
    public Map<String, Object> decide(
            @Name("state") Object state,
            @Name("questions") Map<String, Object> questions,
            @Name(value = "options", defaultValue = "{}") Map<String, Object> options) {
        return CLIENT.decide(state, questions, options);
    }
}
