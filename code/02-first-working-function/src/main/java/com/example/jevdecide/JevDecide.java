package com.example.jevdecide;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.UserFunction;

import java.util.Map;

/**
 * jev.decide(state, questions, options) - decisions from Jev, callable from Cypher.
 * A thin wrapper: the work happens in JevClient.
 */
public class JevDecide {

    private static final String KEY_NAME = "TYPESAFE_API_KEY";

    static final JevClient CLIENT = new JevClient(
            new JdkHttpTransport(),
            new ObjectMapper(),
            JevDecide::resolveApiKey);

    /** Environment variable first, then a JVM property. */
    private static String resolveApiKey() {
        String key = System.getenv(KEY_NAME);
        if (key == null || key.isBlank()) {
            key = System.getProperty(KEY_NAME);
        }
        return key;
    }

    @UserFunction("jev.decide")
    @Description("Ask Jev typed questions about a state. Returns {answers, error_message, metadata}. "
            + "In this first version a failure raises an error.")
    public Map<String, Object> decide(
            @Name("state") Object state,
            @Name("questions") Map<String, Object> questions,
            @Name(value = "options", defaultValue = "{}") Map<String, Object> options) {
        return CLIENT.decide(state, questions, options);
    }
}
