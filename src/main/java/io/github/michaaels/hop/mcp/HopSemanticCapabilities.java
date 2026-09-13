package io.github.michaaels.hop.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine-readable contract for native semantic operations. */
final class HopSemanticCapabilities {
  static final List<String> OPERATION_NAMES =
      List.of(
          "set_name",
          "set_description",
          "rename_component",
          "move_component",
          "add_hop",
          "remove_hop",
          "set_hop_enabled",
          "remove_component");

  private HopSemanticCapabilities() {}

  static Map<String, Object> describe(boolean mutationEnabled, boolean liveUiAvailable) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("product", "Apache Hop Native Semantic MCP");
    result.put("definition_kinds", List.of("pipeline", "workflow"));
    result.put("semantic_operations", operations());
    result.put("preview_available", true);
    result.put("apply_enabled", mutationEnabled);
    result.put("transactional_write", true);
    result.put("sha256_precondition", true);
    result.put("native_reload_validation", true);
    result.put("rollback", "same_mcp_session");
    result.put("live_ui_available", liveUiAvailable);
    result.put("live_ui_status", liveUiAvailable ? "connected" : "headless_not_connected");
    result.put("live_ui_adapter", "project_event_bridge");
    result.put("live_ui_dirty_tab_policy", "never_overwrite");
    result.put("live_ui_supported_clients", List.of("desktop"));
    result.put("tested_hop_versions", List.of("2.19.0", "2.20.0-SNAPSHOT"));
    return result;
  }

  private static List<Map<String, Object>> operations() {
    return List.of(
        operation("set_name", List.of("value"), List.of(), false),
        operation("set_description", List.of("value"), List.of(), false),
        operation("rename_component", List.of("component", "new_name"), List.of(), false),
        operation("move_component", List.of("component", "x", "y"), List.of(), false),
        operation(
            "add_hop",
            List.of("from", "to"),
            List.of("enabled", "evaluation", "unconditional"),
            false),
        operation("remove_hop", List.of("from", "to"), List.of(), true),
        operation("set_hop_enabled", List.of("from", "to", "enabled"), List.of(), false),
        operation("remove_component", List.of("component"), List.of(), true));
  }

  private static Map<String, Object> operation(
      String name, List<String> required, List<String> optional, boolean destructive) {
    return Map.of(
        "operation", name,
        "definition_kinds", List.of("pipeline", "workflow"),
        "required", required,
        "optional", optional,
        "destructive", destructive);
  }
}
