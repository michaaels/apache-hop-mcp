package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;

final class HopMcpService {
  private final ProjectFiles files;
  private final IVariables variables;
  private final IHopMetadataProvider metadataProvider;
  private final boolean allowDeepCheck;
  private final boolean allowWebApi;
  private final HopWebClient webClient;

  HopMcpService(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      boolean allowDeepCheck) {
    this(files, variables, metadataProvider, allowDeepCheck, false, null);
  }

  HopMcpService(
      ProjectFiles files,
      IVariables variables,
      IHopMetadataProvider metadataProvider,
      boolean allowDeepCheck,
      boolean allowWebApi,
      HopWebClient webClient) {
    this.files = files;
    this.variables = variables;
    this.metadataProvider = metadataProvider;
    this.allowDeepCheck = allowDeepCheck;
    this.allowWebApi = allowWebApi;
    this.webClient = webClient;
  }

  Map<String,Object> config() {
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("version", "0.3.1");
    result.put("project_root", files.root().toString());
    result.put("transport", "stdio");
    result.put("read_only", true);
    result.put("allow_deep_check", allowDeepCheck);
    result.put("allow_web_api", allowWebApi);
    result.put("web_api_configured", webClient != null);
    result.put("web_api_base", webClient == null ? "" : webClient.baseUrl());
    result.put("max_read_bytes", ProjectFiles.MAX_READ_BYTES);
    result.put("max_scan_files", ProjectFiles.MAX_SCAN_FILES);
    return result;
  }
  Map<String,Object> plugins() { return HopNative.plugins(); }
  Map<String,Object> plugins(String type,String query,int offset,int limit) { return HopNative.plugins(type,query,offset,limit); }
  Map<String,Object> catalog(String glob,int offset,int limit) throws Exception { return files.catalog(glob,offset,limit); }
  Map<String,Object> listDefinitions() throws Exception { List<Map<String,Object>> defs=new ArrayList<>(); for(Path p:files.definitions()) defs.add(Map.of("path",files.relative(p),"type",p.toString().toLowerCase().endsWith(".hpl")?"pipeline":"workflow")); return Map.of("definitions",defs,"count",defs.size()); }
  Map<String,Object> inspect(String path) throws Exception { return HopXml.inspect(path,readDefinition(path)); }
  Map<String,Object> context(String path) throws Exception { String definition=readDefinition(path); Map<String,Object> out=new LinkedHashMap<>(); out.put("path",path); out.put("type",path.toLowerCase(Locale.ROOT).endsWith(".hpl")?"pipeline":"workflow"); out.put("inspection",safe("inspect",()->HopXml.inspect(path,definition))); out.put("validation",safe("validate",()->HopXml.validate(path,definition))); out.put("dependencies",dependencies(path)); return out; }
  Map<String,Object> component(String path,String component) throws Exception { return HopXml.component(path,readDefinition(path),component); }
  Map<String,Object> validate(String path) throws Exception { return HopXml.validate(path,readDefinition(path)); }
  Map<String,Object> lineage(String path,String component,String direction,int maxDepth) throws Exception { return Map.of("path",path,"component",component,"direction",direction,"edges",HopXml.lineage(readDefinition(path),component,direction,maxDepth)); }
  Map<String,Object> readText(String path) throws Exception { return Map.of("path",path,"text",files.readText(path)); }
  Map<String,Object> search(String query,String glob) throws Exception { var r=files.search(query,glob); return Map.of("query",query,"results",r,"count",r.size()); }
  Map<String,Object> findTable(String table) throws Exception { if(table==null||table.isBlank()) throw new IllegalArgumentException("table is required"); List<Map<String,Object>> matches=new ArrayList<>(); String needle=table.toLowerCase(); for(Path p:files.definitions()) { String rel=files.relative(p), text=files.readText(rel); for(String t:HopXml.findTables(text)) if(t.toLowerCase().contains(needle)) matches.add(Map.of("path",rel,"table",t)); if(matches.size()>=ProjectFiles.MAX_RESULTS) break; } return Map.of("table",table,"matches",matches,"count",matches.size()); }
  Map<String,Object> dependencies(String path) throws Exception { String xml=readDefinition(path); Set<String> refs=new LinkedHashSet<>(HopXml.references(xml)); List<Map<String,Object>> resolved=new ArrayList<>(); for(String ref:refs){ Map<String,Object> row=new LinkedHashMap<>(); row.put("reference",ref); try { Path p=resolveReference(path,ref); row.put("resolved",files.relative(p)); row.put("exists",true); } catch(Exception e){ row.put("exists",false); row.put("error",HopXml.redact(String.valueOf(e.getMessage()))); } resolved.add(row); } return Map.of("path",path,"dependencies",resolved,"count",resolved.size()); }
  Map<String,Object> deepCheck(String path) throws Exception { if(!allowDeepCheck) throw new SecurityException("Deep check disabled. Restart with --allow-deep-check; it may access configured external systems."); return HopNative.deepCheck(resolveDefinition(path),variables,metadataProvider); }

  Map<String,Object> webRequest(String method, String path, Map<String,String> headers)
      throws Exception {
    if (!allowWebApi) {
      throw new SecurityException(
          "Hop Web REST API disabled. Restart with --allow-web-api and configure --web-url or HOP_MCP_WEB_URL.");
    }
    if (webClient == null) {
      throw new IllegalStateException(
          "Hop Web REST API is not configured. Set --web-url or HOP_MCP_WEB_URL.");
    }
    String requestMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
    if (!Set.of("GET", "HEAD").contains(requestMethod)) {
      throw new SecurityException("Only GET and HEAD Hop Web requests are allowed in 0.3.x");
    }
    return webClient.request(requestMethod, path, headers);
  }

  private String readDefinition(String path) throws Exception { return files.readText(validateDefinition(path)); }
  private Path resolveDefinition(String path) throws Exception { return files.resolve(validateDefinition(path)); }
  private Path resolveReference(String definitionPath,String reference) throws Exception { Path definition=resolveDefinition(definitionPath); String resolvedReference=reference.replace("${PROJECT_HOME}",files.root().toString()); if(variables!=null)resolvedReference=variables.resolve(resolvedReference); resolvedReference=resolvedReference.replace("${Internal.Entry.Current.Folder}",definition.getParent().toString()); Path candidate=definition.getParent().resolve(resolvedReference).normalize(); if(!candidate.startsWith(files.root()))throw new IllegalArgumentException("Dependency escapes project root: "+reference); return files.resolve(files.relative(candidate)); }
  private Map<String,Object> safe(String operation,CheckedMap call) { try { return call.call(); } catch(Exception e) { return Map.of("ok",false,"operation",operation,"error",e.getClass().getSimpleName(),"message",HopXml.redact(String.valueOf(e.getMessage()))); } }
  private String validateDefinition(String path) { if(path==null||path.isBlank())throw new IllegalArgumentException("path is required"); String lower=path.toLowerCase(Locale.ROOT); if(!lower.endsWith(".hpl")&&!lower.endsWith(".hwf"))throw new IllegalArgumentException("Definition must be an .hpl or .hwf file: "+path); return path; }
  @FunctionalInterface private interface CheckedMap { Map<String,Object> call() throws Exception; }
}
