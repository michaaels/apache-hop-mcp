package io.github.michaaels.hop.mcp;

import java.util.List;
import java.util.Map;

/**
 * Boundary between headless semantic changes and a future Hop Desktop/RAP session adapter.
 * Implementations must enqueue UI work on the owning UI session and return immediately.
 */
interface HopSemanticEventSink {
  HopSemanticEventSink NONE = event -> false;

  boolean publish(Event event);

  record Event(
      String type,
      String path,
      String definitionKind,
      String transactionId,
      String oldSha256,
      String newSha256,
      List<Map<String, Object>> changes) {
    public Event {
      changes = changes == null ? List.of() : List.copyOf(changes);
    }
  }
}
