package com.example.jevdecide;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real transport built on Java's own HttpClient. The connect timeout belongs to
 * the client, not the request, so we keep one client per connect timeout value.
 */
public class JdkHttpTransport implements HttpTransport {

    private final ConcurrentHashMap<Long, HttpClient> clients = new ConcurrentHashMap<>();

    private HttpClient clientFor(Duration connectTimeout) {
        return clients.computeIfAbsent(connectTimeout.toMillis(), ms ->
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(ms)).build());
    }

    @Override
    public Response post(URI uri,
                         Map<String, String> headers,
                         String body,
                         Duration connectTimeout,
                         Duration requestTimeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);

        HttpResponse<String> response =
                clientFor(connectTimeout).send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }
}
