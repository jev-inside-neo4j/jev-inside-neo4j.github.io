package com.example.jevdecide;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A scripted transport for tests. Each queued outcome is either a Response to
 * return or an IOException to throw. When the queue runs out, the last outcome repeats.
 */
class FakeTransport implements HttpTransport {

    record Request(URI uri, Map<String, String> headers, String body, Duration connect, Duration request) {}

    final List<Request> requests = new ArrayList<>();
    private final Deque<Object> outcomes = new ArrayDeque<>();
    private Object last;

    FakeTransport respond(int status, String body) {
        outcomes.add(new Response(status, body));
        return this;
    }

    FakeTransport fail(IOException e) {
        outcomes.add(e);
        return this;
    }

    @Override
    public Response post(URI uri, Map<String, String> headers, String body,
                         Duration connectTimeout, Duration requestTimeout) throws IOException {
        requests.add(new Request(uri, headers, body, connectTimeout, requestTimeout));
        Object next = outcomes.isEmpty() ? last : outcomes.poll();
        last = next;
        if (next instanceof IOException e) {
            throw e;
        }
        return (Response) next;
    }
}
