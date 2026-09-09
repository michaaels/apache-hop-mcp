package io.github.michaaels.hop.mcp;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.Const;
import org.apache.hop.core.config.plugin.ConfigPlugin;
import org.apache.hop.core.config.plugin.IConfigOptions;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.hop.Hop;
import org.apache.hop.hop.plugin.HopCommand;
import org.apache.hop.hop.plugin.IHopCommand;
import org.apache.hop.metadata.api.IHasHopMetadataProvider;
import org.apache.hop.metadata.serializer.multi.MultiMetadataProvider;
import picocli.CommandLine;

@CommandLine.Command(name = "mcp", mixinStandardHelpOptions = true, description = "Run the Apache Hop MCP server (read-only by default)")
@HopCommand(id = "mcp", description = "Run the Apache Hop Model Context Protocol server")
public class HopMcpCommand implements Runnable, IHopCommand, IHasHopMetadataProvider {
  @CommandLine.Option(names={"--root"}, description="Project root. Defaults to PROJECT_HOME, then current directory.") private String root;
  @CommandLine.Option(names={"--allow-deep-check"}, description="Enable Hop native deep checks. They may access configured external systems.") private boolean allowDeepCheck;
  @CommandLine.Option(names={"--allow-web-api"}, description="Enable read-only requests to the configured Hop Web REST API.") private boolean allowWebApi;
  @CommandLine.Option(names={"--web-url"}, description="Hop Web base URL. Defaults to HOP_MCP_WEB_URL.") private String webUrl;
  @CommandLine.Option(names={"--web-timeout-seconds"}, description="Hop Web request timeout in seconds (1-120).", defaultValue="30") private int webTimeoutSeconds;
  private CommandLine cmd; private IVariables variables; private MultiMetadataProvider metadataProvider; private ILogChannel log;
  @Override public void initialize(CommandLine cmd, IVariables variables, MultiMetadataProvider metadataProvider) throws HopException { this.cmd=cmd; this.variables=variables; this.metadataProvider=metadataProvider; this.log=new LogChannel("ApacheHopMCP"); Hop.addMixinPlugins(cmd, ConfigPlugin.CATEGORY_RUN); }
  @Override public void run() {
    PrintStream protocolOut=System.out;
    PrintStream previousOut=System.out;
    PrintStream previousHopOut=HopLogStore.OriginalSystemOut;
    HopLogStore.OriginalSystemOut=HopLogStore.OriginalSystemErr;
    System.setOut(System.err);
    try { System.setProperty(Const.HOP_PLATFORM_RUNTIME,"MCP"); handleMixinActions(); String configured=StringUtils.isNotBlank(root)?variables.resolve(root):variables.getVariable("PROJECT_HOME"); if(StringUtils.isBlank(configured)) configured=System.getProperty("user.dir"); ProjectFiles files=new ProjectFiles(Path.of(configured)); String configuredWebUrl=StringUtils.isNotBlank(webUrl)?variables.resolve(webUrl):variables.getVariable("HOP_MCP_WEB_URL"); HopWebClient webClient=StringUtils.isBlank(configuredWebUrl)?null:new HopWebClient(configuredWebUrl,variables.getVariable("HOP_MCP_WEB_USERNAME"),variables.getVariable("HOP_MCP_WEB_PASSWORD"),variables.getVariable("HOP_MCP_WEB_BEARER_TOKEN"),webTimeoutSeconds); if(allowWebApi&&webClient==null) throw new IllegalArgumentException("--allow-web-api requires --web-url or HOP_MCP_WEB_URL"); HopMcpService service=new HopMcpService(files,variables,metadataProvider,allowDeepCheck,allowWebApi,webClient); log.logBasic("Apache Hop MCP 0.3.1 started (stdio, read-only) root="+files.root()); if(allowDeepCheck) log.logBasic("Native deep check enabled; checks can contact configured external systems."); if(allowWebApi) log.logBasic("Read-only Hop Web REST access enabled for "+webClient.baseUrl()); try(HopMcpServer server=new HopMcpServer(service,System.in,protocolOut)) { server.awaitEof(); } }
    catch(Exception e) { log.logError("Apache Hop MCP failed",e); throw new RuntimeException(e); }
    finally { System.setOut(previousOut); HopLogStore.OriginalSystemOut=previousHopOut; }
  }
  private void handleMixinActions() throws HopException { for(Map.Entry<String,Object> entry:cmd.getMixins().entrySet()) if(entry.getValue() instanceof IConfigOptions options) options.handleOption(log,this,variables); }
  @Override public MultiMetadataProvider getMetadataProvider(){return metadataProvider;}
  @Override public void setMetadataProvider(MultiMetadataProvider metadataProvider){this.metadataProvider=metadataProvider;}
}
