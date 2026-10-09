package com.example.jevdecide;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * The only thing JevClient needs from the network. Tests supply a fake.
 */
public interface HttpTransport {

    record Response(int status, String body) {}

    Response post(URI uri,
                  Map<String, String> headers,
                  String body,
                  Duration connectTimeout,
                  Duration requestTimeout) throws IOException, InterruptedException;
}
