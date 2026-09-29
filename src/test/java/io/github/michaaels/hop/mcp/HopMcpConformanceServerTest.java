package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopMcpConformanceServerTest {
  @TempDir Path root;

  @Test
  void idleSseGetFlushesHeadersBeforeAnyEvent() throws Exception {
    var transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapperSupplier().get())
            .mcpEndpoint("/mcp")
            .securityValidator(
                DefaultServerTransportSecurityValidator.builder()
                    .allowedHost("127.0.0.1:*")
                    .allowedOrigin("http://127.0.0.1:*")
                    .build())
            .build();
    var service =
        new HopMcpService(
            new ProjectFiles(root), new Variables(), new MemoryMetadataProvider(), false);
    var tomcat = HopMcpConformanceServer.createTomcat(transport, 0);
    try (var server =
            HopMcpServer.withSpecificationFactory(
                service, (mapper, input, output) -> McpServer.sync(transport));
        var client = HttpClient.newHttpClient()) {
      tomcat.start();
      URI endpoint =
          URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp");
      var init =
          client.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(Duration.ofSeconds(5))
                  .header("Content-Type", "application/json")
                  .header("Accept", "application/json, text/event-stream")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"idle-get-test","version":"1"}}}
              """))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, init.statusCode());
      String session = init.headers().firstValue("mcp-session-id").orElseThrow();
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofSeconds(5))
              .header("Accept", "text/event-stream")
              .header("mcp-session-id", session)
              .header("mcp-protocol-version", "2025-11-25")
              .GET()
              .build();
      var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
      try {
        var events = pending.get(3, TimeUnit.SECONDS);
        try (var body = events.body()) {
          assertEquals(200, events.statusCode());
          assertTrue(
              events
                  .headers()
                  .firstValue("Content-Type")
                  .orElseThrow()
                  .startsWith("text/event-stream"));
        }
      } finally {
        pending.cancel(true);
      }
    } finally {
      tomcat.stop();
      tomcat.destroy();
    }
  }
}
