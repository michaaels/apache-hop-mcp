package io.github.michaaels.hop.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.jackson3.JacksonJsonSchemaValidatorSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

final class HopMcpServer implements AutoCloseable {
  @FunctionalInterface interface Handler { Map<String,Object> call(Map<String,Object> args) throws Exception; }
  private final TrackingInputStream input; private final McpSyncServer server;
  HopMcpServer(HopMcpService service, InputStream in, OutputStream protocolOut) {
    input = new TrackingInputStream(in); var mapper = new JacksonMcpJsonMapperSupplier().get(); var transport = new StdioServerTransportProvider(mapper, input, protocolOut);
    server = McpServer.sync(transport).jsonMapper(mapper).jsonSchemaValidator(new JacksonJsonSchemaValidatorSupplier().get()).serverInfo("apache-hop-mcp", "0.3.1").capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).instructions("Read-only Apache Hop project analysis. Deep checks and Hop Web reads are explicit opt-ins because they may contact configured systems.").build();
    add("hop_config","Show MCP project root, limits and security mode.",schema(Map.of(),List.of()), a->service.config());
    add("hop_plugins","Inspect the Apache Hop plugin registry with optional filtering and pagination.",schema(Map.of("type",str("Optional plugin type short name or fully qualified class name"),"query",str("Optional case-insensitive text in plugin IDs, name, description or category"),"offset",nonNegativeInteger("Number of matching plugins to skip"),"limit",integer("Maximum plugins to return")),List.of()), a->a.isEmpty()?service.plugins():service.plugins(sDefault(a,"type",null),sDefault(a,"query",null),iDefault(a,"offset",0),iDefault(a,"limit",50)));
    add("hop_catalog","Catalog bounded project files with paths, kinds, sizes and SHA-256 fingerprints, without returning contents.",schema(Map.of("glob",str("Optional case-insensitive glob, default **"),"offset",nonNegativeInteger("Number of matching files to skip"),"limit",catalogLimit("Maximum files to return")),List.of()), a->service.catalog(sDefault(a,"glob","**"),iDefault(a,"offset",0),iDefault(a,"limit",100)));
    add("hop_list_definitions","List .hpl pipelines and .hwf workflows under the project root.",schema(Map.of(),List.of()), a->service.listDefinitions());
    add("hop_inspect","Inspect a Hop pipeline/workflow structure, SQL tables and references.",schema(Map.of("path",str("Project-relative .hpl/.hwf path")),List.of("path")), a->service.inspect(s(a,"path")));
    add("hop_context","Build a consolidated safe context containing structure, validation and project-local dependencies.",schema(Map.of("path",str("Project-relative .hpl/.hwf path")),List.of("path")), a->service.context(s(a,"path")));
    add("hop_component","Inspect one transform/action; secret-looking fields are redacted.",schema(Map.of("path",str("Definition path"),"component",str("Transform or action name")),List.of("path","component")), a->service.component(s(a,"path"),s(a,"component")));
    add("hop_component_lineage","Traverse Hop edges upstream or downstream.",schema(Map.of("path",str("Definition path"),"component",str("Start component"),"direction",enumStr("upstream","downstream"),"max_depth",integer("Maximum traversal depth, default 10")),List.of("path","component")), a->service.lineage(s(a,"path"),s(a,"component"),sDefault(a,"direction","downstream"),iDefault(a,"max_depth",10)));
    add("hop_validate","Run safe structural validation without field/database resolution.",schema(Map.of("path",str("Definition path")),List.of("path")), a->service.validate(s(a,"path")));
    add("hop_deep_check","Run Apache Hop's native checker. Disabled unless hop mcp starts with --allow-deep-check; may access external systems.",schema(Map.of("path",str("Definition path")),List.of("path")), a->service.deepCheck(s(a,"path")));
    add("hop_read_text","Read a UTF-8 project file, confined to project root and size limits.",schema(Map.of("path",str("Project-relative path")),List.of("path")), a->service.readText(s(a,"path")));
    add("hop_search","Search text within the project with scan/result limits.",schema(Map.of("query",str("Case-insensitive text"),"glob",str("Optional glob, default **")),List.of("query")), a->service.search(s(a,"query"),sDefault(a,"glob","**")));
    add("hop_find_table","Find SQL table references across Hop definitions.",schema(Map.of("table",str("Table name or substring")),List.of("table")), a->service.findTable(s(a,"table")));
    add("hop_dependencies","Extract referenced .hpl/.hwf definitions and resolve those inside project root.",schema(Map.of("path",str("Definition path")),List.of("path")), a->service.dependencies(s(a,"path")));
    add("hop_web_request","Call a configured Hop Web REST endpoint with GET or HEAD. Disabled unless started with --allow-web-api; paths remain inside the configured base URL and sensitive response data is redacted.",schema(Map.of("method",enumStr("GET","HEAD"),"path",str("Relative REST path, optionally including a query string"),"headers",headersSchema()),List.of("path")), a->service.webRequest(sDefault(a,"method","GET"),s(a,"path"),headers(a.get("headers"))));
  }
  void awaitEof() throws InterruptedException { input.awaitEof(); }
  private void add(String name,String description,Map<String,Object> schema,Handler handler) { var tool=McpSchema.Tool.builder(name,schema).description(description).build(); var spec=McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange,request)->{ try { Map<String,Object> args=request.arguments()==null?Map.of():request.arguments(); Map<String,Object> data=handler.call(args); return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(JsonUtil.toJson(data)))).structuredContent(data).build(); } catch(Exception e) { Map<String,Object> error=Map.of("error",e.getClass().getSimpleName(),"message",HopXml.redact(String.valueOf(e.getMessage()))); return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(JsonUtil.toJson(error)))).structuredContent(error).isError(true).build(); } }).build(); server.addTool(spec); }
  private static Map<String,Object> schema(Map<String,Object> properties,List<String> required){ Map<String,Object> out=new LinkedHashMap<>(); out.put("type","object"); out.put("properties",properties); out.put("additionalProperties",false); if(!required.isEmpty())out.put("required",required); return out; }
  private static Map<String,Object> str(String d){return Map.of("type","string","description",d);} private static Map<String,Object> integer(String d){return Map.of("type","integer","description",d,"minimum",1,"maximum",50);} private static Map<String,Object> enumStr(String...v){return Map.of("type","string","enum",List.of(v));}
  private static Map<String,Object> nonNegativeInteger(String d){return Map.of("type","integer","description",d,"minimum",0,"maximum",ProjectFiles.MAX_SCAN_FILES);}
  private static Map<String,Object> catalogLimit(String d){return Map.of("type","integer","description",d,"minimum",1,"maximum",200);}
  private static Map<String,Object> headersSchema(){return Map.of("type","object","description","Optional request headers; authentication headers are managed by MCP.","additionalProperties",Map.of("type","string"),"maxProperties",32);}
  private static Map<String,String> headers(Object value){if(value==null)return Map.of();if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException("headers must be an object");Map<String,String> out=new LinkedHashMap<>();for(Map.Entry<?,?> entry:map.entrySet()){if(entry.getKey()==null||entry.getValue()==null)throw new IllegalArgumentException("headers cannot contain null keys or values");out.put(String.valueOf(entry.getKey()),String.valueOf(entry.getValue()));}return out;}
  private static String s(Map<String,Object>a,String k){Object v=a.get(k);if(v==null||String.valueOf(v).isBlank())throw new IllegalArgumentException(k+" is required");return String.valueOf(v);} private static String sDefault(Map<String,Object>a,String k,String d){Object v=a.get(k);return v==null?d:String.valueOf(v);} private static int iDefault(Map<String,Object>a,String k,int d){Object v=a.get(k);if(v==null)return d;if(v instanceof Number n)return n.intValue();return Integer.parseInt(String.valueOf(v));}
  @Override public void close(){server.closeGracefully();}
  private static final class TrackingInputStream extends FilterInputStream { private final CountDownLatch eof=new CountDownLatch(1); TrackingInputStream(InputStream in){super(in);} @Override public int read() throws IOException {int r=super.read();if(r<0)eof.countDown();return r;} @Override public int read(byte[] b,int off,int len)throws IOException{int r=super.read(b,off,len);if(r<0)eof.countDown();return r;} @Override public void close()throws IOException{try{super.close();}finally{eof.countDown();}} void awaitEof()throws InterruptedException{eof.await();} }
}
