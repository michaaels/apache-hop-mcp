package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.metadata.api.IHopMetadataProvider;

final class HopMcpService {
  private final ProjectFiles files; private final IVariables variables; private final IHopMetadataProvider metadataProvider; private final boolean allowDeepCheck;
  HopMcpService(ProjectFiles files, IVariables variables, IHopMetadataProvider metadataProvider, boolean allowDeepCheck) { this.files=files; this.variables=variables; this.metadataProvider=metadataProvider; this.allowDeepCheck=allowDeepCheck; }
  Map<String,Object> config() { return Map.of("version","0.3.0","project_root",files.root().toString(),"transport","stdio","read_only",true,"allow_deep_check",allowDeepCheck,"max_read_bytes",ProjectFiles.MAX_READ_BYTES,"max_scan_files",ProjectFiles.MAX_SCAN_FILES); }
  Map<String,Object> plugins() { return HopNative.plugins(); }
  Map<String,Object> listDefinitions() throws Exception { List<Map<String,Object>> defs=new ArrayList<>(); for(Path p:files.definitions()) defs.add(Map.of("path",files.relative(p),"type",p.toString().toLowerCase().endsWith(".hpl")?"pipeline":"workflow")); return Map.of("definitions",defs,"count",defs.size()); }
  Map<String,Object> inspect(String path) throws Exception { return HopXml.inspect(path,files.readText(path)); }
  Map<String,Object> component(String path,String component) throws Exception { return HopXml.component(path,files.readText(path),component); }
  Map<String,Object> validate(String path) throws Exception { return HopXml.validate(path,files.readText(path)); }
  Map<String,Object> lineage(String path,String component,String direction,int maxDepth) throws Exception { return Map.of("path",path,"component",component,"direction",direction,"edges",HopXml.lineage(files.readText(path),component,direction,maxDepth)); }
  Map<String,Object> readText(String path) throws Exception { return Map.of("path",path,"text",files.readText(path)); }
  Map<String,Object> search(String query,String glob) throws Exception { var r=files.search(query,glob); return Map.of("query",query,"results",r,"count",r.size()); }
  Map<String,Object> findTable(String table) throws Exception { if(table==null||table.isBlank()) throw new IllegalArgumentException("table is required"); List<Map<String,Object>> matches=new ArrayList<>(); String needle=table.toLowerCase(); for(Path p:files.definitions()) { String rel=files.relative(p), text=files.readText(rel); for(String t:HopXml.findTables(text)) if(t.toLowerCase().contains(needle)) matches.add(Map.of("path",rel,"table",t)); if(matches.size()>=ProjectFiles.MAX_RESULTS) break; } return Map.of("table",table,"matches",matches,"count",matches.size()); }
  Map<String,Object> dependencies(String path) throws Exception { String xml=files.readText(path); Set<String> refs=new LinkedHashSet<>(HopXml.references(xml)); List<Map<String,Object>> resolved=new ArrayList<>(); for(String ref:refs){ Map<String,Object> row=new LinkedHashMap<>(); row.put("reference",ref); try { Path p=files.resolve(ref); row.put("resolved",files.relative(p)); row.put("exists",true); } catch(Exception e){ row.put("exists",false); } resolved.add(row); } return Map.of("path",path,"dependencies",resolved,"count",resolved.size()); }
  Map<String,Object> deepCheck(String path) throws Exception { if(!allowDeepCheck) throw new SecurityException("Deep check disabled. Restart with --allow-deep-check; it may access configured external systems."); return HopNative.deepCheck(files.resolve(path),variables,metadataProvider); }
}
