package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.ICheckResult;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.workflow.WorkflowMeta;

final class HopNative {
  private HopNative() {}
  static Map<String,Object> plugins() {
    Map<String,Object> out = new LinkedHashMap<>(); List<Map<String,Object>> types = new ArrayList<>(); PluginRegistry registry = PluginRegistry.getInstance(); int total = 0;
    for (Class<? extends IPluginType> type : registry.getPluginTypes()) { List<IPlugin> plugins = registry.getPlugins(type); total += plugins.size(); List<Map<String,Object>> sample = new ArrayList<>(); for (IPlugin plugin : plugins) { if (sample.size() >= 50) break; Map<String,Object> row = new LinkedHashMap<>(); row.put("ids", List.of(plugin.getIds())); row.put("name", String.valueOf(plugin.getName())); row.put("description", String.valueOf(plugin.getDescription())); row.put("category", String.valueOf(plugin.getCategory())); sample.add(row); } Map<String,Object> row = new LinkedHashMap<>(); row.put("type", type.getName()); row.put("count", plugins.size()); row.put("plugins", sample); types.add(row); }
    out.put("plugin_registry", registry.getClass().getName()); out.put("plugin_type_count", types.size()); out.put("plugin_count", total); out.put("plugin_types", types); return out;
  }
  static Map<String,Object> deepCheck(Path file, IVariables variables, IHopMetadataProvider metadataProvider) throws Exception {
    String name = file.getFileName().toString().toLowerCase(); List<ICheckResult> remarks = new ArrayList<>();
    if (name.endsWith(".hpl")) { PipelineMeta meta = new PipelineMeta(file.toString(), metadataProvider, variables); meta.checkTransforms(remarks, false, null, variables, metadataProvider); }
    else if (name.endsWith(".hwf")) { WorkflowMeta meta = new WorkflowMeta(variables, file.toString(), metadataProvider); meta.checkActions(remarks, false, null, variables, metadataProvider); }
    else throw new IllegalArgumentException("Deep check supports .hpl and .hwf only");
    int errors=0,warnings=0,comments=0,ok=0,none=0; List<Map<String,Object>> issues=new ArrayList<>();
    for (ICheckResult r : remarks) { int type=r.getType(); if(type==ICheckResult.TYPE_RESULT_ERROR)errors++; else if(type==ICheckResult.TYPE_RESULT_WARNING)warnings++; else if(type==ICheckResult.TYPE_RESULT_COMMENT)comments++; else if(type==ICheckResult.TYPE_RESULT_OK)ok++; else none++; if(type!=ICheckResult.TYPE_RESULT_OK && issues.size()<500) issues.add(Map.of("type",type,"text",String.valueOf(r.getText()))); }
    return Map.of("checker","apache-hop","deep",true,"may_access_external_systems",true,"valid",errors==0,"summary",Map.of("errors",errors,"warnings",warnings,"comments",comments,"ok",ok,"none",none),"issues",issues);
  }
}
