package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HopXmlTest {
  @TempDir Path temp;
  private static final String PIPELINE="""
      <?xml version="1.0"?>
      <pipeline><info><name>demo</name></info>
      <order><hop><from>Input</from><to>Output</to><enabled>Y</enabled></hop></order>
      <transform><name>Input</name><type>TableInput</type><sql>select * from schema.table_a</sql><password>topsecret</password></transform>
      <transform><name>Output</name><type>TextFileOutput</type><filename>child.hwf</filename></transform>
      </pipeline>
      """;
  @Test void inspectAndRedact() throws Exception { var inspect=HopXml.inspect("demo.hpl",PIPELINE); assertEquals("pipeline",inspect.get("type")); assertTrue(inspect.toString().contains("schema.table_a")); var component=HopXml.component("demo.hpl",PIPELINE,"Input"); assertTrue(component.toString().contains("***REDACTED***")); assertFalse(component.toString().contains("topsecret")); }
  @Test void validatesAndTraverses() throws Exception { assertEquals(true,HopXml.validate("demo.hpl",PIPELINE).get("valid")); var edges=HopXml.lineage(PIPELINE,"Input","downstream",10); assertEquals(1,edges.size()); assertEquals("Output",edges.get(0).get("to")); }
  @Test void blocksXxe() { String xxe="<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><pipeline><info><name>&e;</name></info></pipeline>"; assertThrows(Exception.class,()->HopXml.parse(xxe)); }
  @Test void projectRootRejectsEscape() throws Exception { Files.writeString(temp.resolve("a.txt"),"ok"); ProjectFiles files=new ProjectFiles(temp); assertEquals("ok",files.readText("a.txt")); assertThrows(Exception.class,()->files.resolve("../outside")); }
}
