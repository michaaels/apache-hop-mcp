package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HopWebClientTest {
  private HttpServer server;
  private AtomicReference<String> requestPath;
  private AtomicReference<String> authorization;

  @BeforeEach
  void setUp() throws Exception {
    requestPath = new AtomicReference<>();
    authorization = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/hop",
        exchange -> {
          requestPath.set(exchange.getRequestURI().toString());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().add("Set-Cookie", "session=secret");
          exchange.getResponseHeaders().add("X-Api-Key", "header-secret");
          byte[] response =
              "{\"ok\":true,\"token\":\"server-secret\"}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void requestUsesConfiguredBasePathAndRedactsSensitiveData() throws Exception {
    HopWebClient client = new HopWebClient(baseUrl(), "user", "pass", null, 5);

    Map<String, Object> result =
        client.request("GET", "/status?token=caller-secret", Map.of());

    assertEquals("/hop/status?token=caller-secret", requestPath.get());
    assertEquals("Basic dXNlcjpwYXNz", authorization.get());
    assertEquals(200, result.get("status"));
    assertTrue((Boolean) result.get("ok"));
    assertFalse(String.valueOf(result.get("url")).contains("caller-secret"));
    assertFalse(String.valueOf(result.get("body")).contains("server-secret"));
    assertTrue(String.valueOf(result.get("body")).contains("***REDACTED***"));
    Map<?, ?> headers = (Map<?, ?>) result.get("headers");
    assertTrue(headers.containsValue("[REDACTED]"));
    assertEquals("[REDACTED]", headers.get("x-api-key"));
  }

  @Test
  void requestRejectsTraversalAbsoluteUrlsAndMutatingMethods() {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "../status", Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client.request("GET", "http://127.0.0.1/status", Map.of()));
    assertThrows(
        SecurityException.class,
        () -> client.request("POST", "/status", Map.of()));
  }

  @Test
  void requestRejectsCallerSuppliedAuthenticationHeaders() {
    HopWebClient client = new HopWebClient(baseUrl(), null, null, null, 5);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            client.request(
                "GET", "/status", Map.of("Authorization", "Bearer secret")));
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hop";
  }
}
