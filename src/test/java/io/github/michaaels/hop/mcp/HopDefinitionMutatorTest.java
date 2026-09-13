package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.pipeline.transforms.dummy.DummyMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.actions.dummy.ActionDummy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopDefinitionMutatorTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  void createsNativePipelineOnlyAfterApplyAndCanRollbackCreation() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    ProjectFiles files = new ProjectFiles(project);
    HopDefinitionMutator mutator = new HopDefinitionMutator(files, variables, metadataProvider);
    List<Map<String, Object>> operations =
        List.of(
            Map.of("operation", "set_name", "value", "Generated pipeline"),
            Map.of("operation", "set_description", "value", "Created semantically"));

    Map<String, Object> preview =
        mutator.mutate("generated.hpl", "pipeline", operations, null, false);

    assertEquals(true, preview.get("preview"));
    assertEquals(true, preview.get("native_reload_valid"));
    assertFalse(Files.exists(project.resolve("generated.hpl")));

    Map<String, Object> applied =
        mutator.mutate("generated.hpl", "pipeline", operations, null, true);
    assertEquals(true, applied.get("applied"));
    assertEquals(true, applied.get("rollback_available"));

    PipelineMeta pipeline =
        new PipelineMeta(
            Files.newInputStream(project.resolve("generated.hpl")), metadataProvider, variables);
    assertEquals("Generated pipeline", pipeline.getName());
    assertEquals("Created semantically", pipeline.getDescription());

    Map<String, Object> rolledBack =
        mutator.rollback(
            String.valueOf(applied.get("transaction_id")),
            String.valueOf(applied.get("new_sha256")));
    assertEquals(true, rolledBack.get("rolled_back"));
    assertFalse(Files.exists(project.resolve("generated.hpl")));
  }

  @Test
  void mutatesExistingWorkflowWithHashBackupReloadAndRollback() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("flow.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Original workflow");
    byte[] originalXml = original.getXml(variables).getBytes(StandardCharsets.UTF_8);
    Files.write(project.resolve("flow.hwf"), originalXml);

    ProjectFiles files = new ProjectFiles(project);
    HopDefinitionMutator mutator = new HopDefinitionMutator(files, variables, metadataProvider);
    String oldHash = ProjectFiles.sha256(originalXml);
    List<Map<String, Object>> operations =
        List.of(Map.of("operation", "set_name", "value", "Corrected workflow"));

    assertThrows(
        Exception.class, () -> mutator.mutate("flow.hwf", "workflow", operations, "wrong", true));

    Map<String, Object> preview = mutator.mutate("flow.hwf", "workflow", operations, null, false);
    assertEquals(false, preview.get("applied"));
    assertEquals(oldHash, ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hwf"))));

    Map<String, Object> applied = mutator.mutate("flow.hwf", "workflow", operations, oldHash, true);
    assertTrue(Files.isRegularFile(project.resolve(String.valueOf(applied.get("backup")))));
    WorkflowMeta corrected =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("flow.hwf")), metadataProvider, variables);
    assertEquals("Corrected workflow", corrected.getName());

    assertThrows(
        Exception.class,
        () -> mutator.rollback(String.valueOf(applied.get("transaction_id")), oldHash));
    mutator.rollback(
        String.valueOf(applied.get("transaction_id")), String.valueOf(applied.get("new_sha256")));
    WorkflowMeta restored =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("flow.hwf")), metadataProvider, variables);
    assertEquals("Original workflow", restored.getName());
    assertEquals(oldHash, ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hwf"))));
  }

  @Test
  void serviceRequiresExplicitOptInForExecutionAndAppliedMutation() throws Exception {
    Files.writeString(project.resolve("flow.hpl"), "<pipeline/>");
    HopMcpService service = new HopMcpService(new ProjectFiles(project), null, null, false);
    try {
      assertThrows(
          SecurityException.class, () -> service.execute("flow.hpl", "local", Map.of(), 30));
      assertThrows(SecurityException.class, () -> service.logs(null, true, -1, 0));
      assertThrows(
          SecurityException.class,
          () ->
              service.mutateDefinition(
                  "flow.hpl",
                  "pipeline",
                  List.of(Map.of("operation", "set_name", "value", "Changed")),
                  ProjectFiles.sha256(Files.readAllBytes(project.resolve("flow.hpl"))),
                  true));
    } finally {
      service.close();
    }
  }

  @Test
  void rejectsTraversalKindMismatchAndTooManyOperations() throws Exception {
    Variables variables = new Variables();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project), variables, new MemoryMetadataProvider());
    List<Map<String, Object>> tooMany =
        java.util.stream.IntStream.rangeClosed(0, HopDefinitionMutator.MAX_OPERATIONS)
            .mapToObj(i -> Map.<String, Object>of("operation", "set_name", "value", "n" + i))
            .toList();

    assertThrows(
        Exception.class, () -> mutator.mutate("../escape.hpl", "pipeline", List.of(), null, false));
    assertThrows(
        Exception.class, () -> mutator.mutate("flow.hpl", "workflow", List.of(), null, false));
    assertThrows(
        Exception.class, () -> mutator.mutate("flow.hpl", "pipeline", tooMany, null, false));
  }

  @Test
  void appliesStructuralPipelineOperationsThroughNativeHopObjectsAndPublishesEvent()
      throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    PipelineMeta original = new PipelineMeta();
    original.setFilename(project.resolve("structural.hpl").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Structural pipeline");
    original.addTransform(new TransformMeta("Dummy", "Input", new DummyMeta()));
    original.addTransform(new TransformMeta("Dummy", "Output", new DummyMeta()));
    Files.writeString(project.resolve("structural.hpl"), original.getXml(variables));

    List<HopSemanticEventSink.Event> events = new ArrayList<>();
    HopDefinitionMutator mutator =
        new HopDefinitionMutator(
            new ProjectFiles(project),
            variables,
            metadataProvider,
            event -> {
              events.add(event);
              return true;
            });
    String oldHash = ProjectFiles.sha256(Files.readAllBytes(project.resolve("structural.hpl")));

    Map<String, Object> applied =
        mutator.mutate(
            "structural.hpl",
            "pipeline",
            List.of(
                Map.of("operation", "move_component", "component", "Input", "x", 120, "y", 240),
                Map.of(
                    "operation", "add_hop",
                    "from", "Input",
                    "to", "Output",
                    "enabled", false)),
            oldHash,
            true);

    PipelineMeta changed =
        new PipelineMeta(
            Files.newInputStream(project.resolve("structural.hpl")), metadataProvider, variables);
    TransformMeta input = changed.findTransform("Input");
    assertEquals(120, input.getLocation().x);
    assertEquals(240, input.getLocation().y);
    assertEquals(1, changed.nrPipelineHops());
    PipelineHopMeta hop = changed.getPipelineHop(0);
    assertEquals("Input", hop.getFromTransform().getName());
    assertEquals("Output", hop.getToTransform().getName());
    assertFalse(hop.isEnabled());
    assertEquals(true, applied.get("semantic_event_published"));
    assertEquals(1, events.size());
    assertEquals("mutation_applied", events.get(0).type());

    Map<String, Object> removed =
        mutator.mutate(
            "structural.hpl",
            "pipeline",
            List.of(
                Map.of(
                    "operation", "remove_hop",
                    "from", "Input",
                    "to", "Output"),
                Map.of("operation", "remove_component", "component", "Output")),
            String.valueOf(applied.get("new_sha256")),
            true);
    PipelineMeta withoutOutput =
        new PipelineMeta(
            Files.newInputStream(project.resolve("structural.hpl")), metadataProvider, variables);
    assertEquals(1, withoutOutput.nrTransforms());
    assertEquals(0, withoutOutput.nrPipelineHops());

    Map<String, Object> rolledBack =
        mutator.rollback(
            String.valueOf(removed.get("transaction_id")),
            String.valueOf(removed.get("new_sha256")));
    assertEquals(true, rolledBack.get("semantic_event_published"));
    assertEquals("mutation_rolled_back", events.get(2).type());
  }

  @Test
  void reportsSemanticCapabilitiesAndHopCompatibility() {
    Map<String, Object> capabilities = HopSemanticCapabilities.describe(true, false);

    assertEquals("Apache Hop Native Semantic MCP", capabilities.get("product"));
    assertEquals(List.of("pipeline", "workflow"), capabilities.get("definition_kinds"));
    assertTrue(((List<?>) capabilities.get("semantic_operations")).size() >= 8);
    assertEquals(List.of("2.19.0", "2.20.0-SNAPSHOT"), capabilities.get("tested_hop_versions"));
    assertEquals(false, capabilities.get("live_ui_available"));
    assertEquals("headless_not_connected", capabilities.get("live_ui_status"));
  }

  @Test
  void appliesStructuralWorkflowOperationsThroughNativeHopObjects() throws Exception {
    Variables variables = new Variables();
    MemoryMetadataProvider metadataProvider = new MemoryMetadataProvider();
    WorkflowMeta original = new WorkflowMeta();
    original.setFilename(project.resolve("structural.hwf").toString());
    original.setNameSynchronizedWithFilename(false);
    original.setName("Structural workflow");
    ActionDummy startAction = new ActionDummy();
    startAction.setName("Start");
    ActionDummy finishAction = new ActionDummy();
    finishAction.setName("Finish");
    original.addAction(new ActionMeta(startAction));
    original.addAction(new ActionMeta(finishAction));
    Files.writeString(project.resolve("structural.hwf"), original.getXml(variables));

    HopDefinitionMutator mutator =
        new HopDefinitionMutator(new ProjectFiles(project), variables, metadataProvider);
    String oldHash = ProjectFiles.sha256(Files.readAllBytes(project.resolve("structural.hwf")));
    Map<String, Object> applied =
        mutator.mutate(
            "structural.hwf",
            "workflow",
            List.of(
                Map.of("operation", "move_component", "component", "Start", "x", 75, "y", 125),
                Map.of(
                    "operation", "add_hop",
                    "from", "Start",
                    "to", "Finish",
                    "enabled", true,
                    "unconditional", true)),
            oldHash,
            true);

    WorkflowMeta changed =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("structural.hwf")), metadataProvider, variables);
    assertEquals(75, changed.findAction("Start").getLocation().x);
    assertEquals(125, changed.findAction("Start").getLocation().y);
    assertEquals(1, changed.nrWorkflowHops());
    assertTrue(changed.getWorkflowHop(0).isEnabled());
    assertTrue(changed.getWorkflowHop(0).isUnconditional());

    mutator.rollback(
        String.valueOf(applied.get("transaction_id")), String.valueOf(applied.get("new_sha256")));
    WorkflowMeta restored =
        new WorkflowMeta(
            Files.newInputStream(project.resolve("structural.hwf")), metadataProvider, variables);
    assertEquals(0, restored.nrWorkflowHops());
  }
}
