package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
  static Map<String,Object> plugins(String typeFilter, String query, int offset, int limit) {
    if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
    String normalizedType=typeFilter==null||typeFilter.isBlank()?null:typeFilter.toLowerCase(Locale.ROOT);
    String normalizedQuery=query==null||query.isBlank()?null:query.toLowerCase(Locale.ROOT);
    PluginRegistry registry=PluginRegistry.getInstance();
    List<Map<String,Object>> types=new ArrayList<>();
    List<Map<String,Object>> results=new ArrayList<>();
    int total=0, matched=0;
    for(Class<? extends IPluginType> type:registry.getPluginTypes()) {
      List<IPlugin> registered=registry.getPlugins(type);
      total+=registered.size();
      types.add(Map.of("type",type.getName(),"count",registered.size()));
      if(!matchesType(type,normalizedType)) continue;
      for(IPlugin plugin:registered) {
        if(!matchesQuery(plugin,normalizedQuery)) continue;
        if(matched>=offset && results.size()<limit) results.add(pluginRow(type.getName(),plugin));
        matched++;
      }
    }
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("plugin_registry",registry.getClass().getName());
    out.put("plugin_type_count",types.size());
    out.put("plugin_count",total);
    out.put("matched_plugin_count",matched);
    out.put("returned_plugin_count",results.size());
    out.put("offset",offset);
    out.put("limit",limit);
    out.put("has_more",offset+results.size()<matched);
    if(normalizedType!=null) out.put("type_filter",typeFilter);
    if(normalizedQuery!=null) out.put("query",query);
    out.put("plugin_types",types);
    out.put("plugins",results);
    return out;
  }
  private static boolean matchesType(Class<? extends IPluginType> type,String filter) { return filter==null||type.getName().toLowerCase(Locale.ROOT).equals(filter)||type.getSimpleName().toLowerCase(Locale.ROOT).equals(filter); }
  private static boolean matchesQuery(IPlugin plugin,String query) { if(query==null)return true; if(contains(plugin.getName(),query)||contains(plugin.getDescription(),query)||contains(plugin.getCategory(),query))return true; if(plugin.getIds()!=null)for(String id:plugin.getIds())if(contains(id,query))return true; return false; }
  private static boolean contains(Object value,String query) { return value!=null&&String.valueOf(value).toLowerCase(Locale.ROOT).contains(query); }
  private static Map<String,Object> pluginRow(String type,IPlugin plugin) { Map<String,Object> row=new LinkedHashMap<>(); row.put("type",type); row.put("ids",List.of(plugin.getIds())); row.put("name",String.valueOf(plugin.getName())); row.put("description",String.valueOf(plugin.getDescription())); row.put("category",String.valueOf(plugin.getCategory())); return row; }
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
