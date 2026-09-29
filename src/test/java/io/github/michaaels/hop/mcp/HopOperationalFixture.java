package io.github.michaaels.hop.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.encryption.Encr;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.execution.ExecutionInfoLocation;
import org.apache.hop.execution.local.FileExecutionInfoLocation;
import org.apache.hop.metadata.serializer.json.JsonMetadataProvider;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.workflow.config.WorkflowRunConfiguration;
import org.apache.hop.workflow.engines.local.LocalWorkflowRunConfiguration;

/** Test-only bootstrap of native, local metadata; definitions are authored through MCP. */
public final class HopOperationalFixture {
  private HopOperationalFixture() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Expected a new project directory");
    Path project = Path.of(args[0]).toAbsolutePath().normalize();
    Files.createDirectory(project);
    HopEnvironment.init();
    var provider =
        new JsonMetadataProvider(
            Encr.getEncoder(), project.resolve("metadata").toString(), new Variables());
    var location = new ExecutionInfoLocation();
    location.setName("acceptance-files");
    var storage = new FileExecutionInfoLocation(project.resolve("execution-info").toString());
    storage.setCreateParentFolder(true);
    location.setExecutionInfoLocation(storage);
    provider.getSerializer(ExecutionInfoLocation.class).save(location);
    var pipeline = new PipelineRunConfiguration();
    pipeline.setName("local");
    pipeline.setExecutionInfoLocationName(location.getName());
    var engine = new LocalPipelineRunConfiguration();
    engine.setEnginePluginId("local");
    engine.setGatheringMetrics(true);
    pipeline.setEngineRunConfiguration(engine);
    provider.getSerializer(PipelineRunConfiguration.class).save(pipeline);
    var workflow = new WorkflowRunConfiguration();
    workflow.setName("local");
    workflow.setExecutionInfoLocationName(location.getName());
    var workflowEngine = new LocalWorkflowRunConfiguration();
    workflowEngine.setEnginePluginId("local");
    workflow.setEngineRunConfiguration(workflowEngine);
    provider.getSerializer(WorkflowRunConfiguration.class).save(workflow);
    Files.writeString(project.resolve(".hop-mcp-acceptance"), "disposable-local-fixture-v1\n");
    System.out.println("Native local acceptance metadata created; no external connections.");
  }
}
