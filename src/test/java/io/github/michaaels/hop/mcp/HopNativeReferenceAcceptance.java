package io.github.michaaels.hop.mcp;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.HopMetadataPropertyType;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;

/** Distribution-only acceptance gate: never skips a missing required transform plugin. */
public final class HopNativeReferenceAcceptance {
  private static final List<String> PLUGINS =
      List.of(
          "TableInput", "TableOutput", "DBLookup", "DBJoin", "InsertUpdate", "Delete", "DBProc");
  private static final Map<HopMetadataPropertyType, List<String>> TYPE_KEYS =
      Map.of(HopMetadataPropertyType.RDBMS_CONNECTION, List.of("rdbms"));

  private HopNativeReferenceAcceptance() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2)
      throw new IllegalArgumentException("Expected fixture directory and installed connector JAR");
    Path installedJar = Path.of(args[1]).toRealPath();
    requireInstalledCodeSource(HopMetadataReferenceExtractor.class, installedJar);
    requireInstalledCodeSource(HopProjectDefinitionIndex.class, installedJar);
    Path fixtures = Path.of(args[0]).toAbsolutePath().normalize();
    Files.createDirectory(fixtures);
    HopEnvironment.init();
    Variables variables = new Variables();
    variables.setVariable("DB_NAME", "WAREHOUSE");
    MemoryMetadataProvider provider = new MemoryMetadataProvider();
    DatabaseMeta database =
        new DatabaseMeta("WAREHOUSE", "H2", "Native", "", "mem:acceptance", "", "sa", "");
    provider.getSerializer(DatabaseMeta.class).save(database);
    HopMetadataReferenceExtractor extractor =
        new HopMetadataReferenceExtractor(provider, variables);
    int cases = 0;
    for (String plugin : PLUGINS) {
      for (String connection : List.of("WAREHOUSE", "${DB_NAME}")) {
        PipelineMeta pipeline = pipeline(provider, plugin);
        pipeline.addTransform(transform(plugin, "Input", connection));
        compare(fixtures, plugin + "-" + cases, pipeline, provider, variables, extractor, 1, false);
        cases++;
      }
    }
    PipelineMeta bounded = pipeline(provider, "bounded");
    for (int index = 0; index < 201; index++) {
      bounded.addTransform(transform("TableInput", "Input-" + index, "WAREHOUSE"));
    }
    compare(fixtures, "bounded", bounded, provider, variables, extractor, 200, true);
    String unavailable =
        "<pipeline><transform><name>Missing</name><type>AcceptanceMissingPlugin</type>"
            + "<connection>WAREHOUSE</connection></transform></pipeline>";
    var fallback = extractor.extract("missing.hpl", HopXml.parse(unavailable));
    if (fallback.references().size() != 1
        || fallback.references().getFirst().source()
            != HopProjectDefinitionIndex.ReferenceSource.TEXT_FALLBACK) {
      throw new IllegalStateException("Missing-plugin fallback changed");
    }
    System.out.println(
        "{\"gate\":\"native_reference_equivalence\",\"cases\":"
            + (cases + 2)
            + ",\"required_plugins\":7,\"passed\":true}");
  }

  private static void requireInstalledCodeSource(Class<?> type, Path installedJar)
      throws Exception {
    var source = type.getProtectionDomain().getCodeSource();
    if (source == null
        || !Path.of(source.getLocation().toURI()).toRealPath().equals(installedJar)) {
      throw new IllegalStateException(
          type.getSimpleName() + " was not loaded from installed connector JAR");
    }
  }

  private static PipelineMeta pipeline(MemoryMetadataProvider provider, String name) {
    PipelineMeta pipeline = new PipelineMeta();
    pipeline.setMetadataProvider(provider);
    pipeline.setName(name);
    return pipeline;
  }

  private static TransformMeta transform(String id, String name, String connection)
      throws Exception {
    var plugin = PluginRegistry.getInstance().findPluginWithId(TransformPluginType.class, id);
    if (plugin == null) throw new IllegalStateException("Required native plugin absent: " + id);
    ITransformMeta meta = PluginRegistry.getInstance().loadClass(plugin, ITransformMeta.class);
    meta.setDefault();
    boolean configured = false;
    for (Class<?> type = meta.getClass(); type != null; type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        HopMetadataProperty property = field.getAnnotation(HopMetadataProperty.class);
        if (property != null
            && property.hopMetadataPropertyType() == HopMetadataPropertyType.RDBMS_CONNECTION
            && field.getType() == String.class
            && field.trySetAccessible()) {
          field.set(meta, connection);
          configured = true;
        }
      }
    }
    if (!configured) throw new IllegalStateException("Native RDBMS property not configured: " + id);
    return new TransformMeta(id, name, meta);
  }

  private static void compare(
      Path fixtures,
      String name,
      PipelineMeta original,
      MemoryMetadataProvider provider,
      Variables variables,
      HopMetadataReferenceExtractor extractor,
      int expectedCount,
      boolean expectedTruncated)
      throws Exception {
    String xml = original.getXml(variables);
    Files.writeString(fixtures.resolve(name + ".hpl"), xml);
    PipelineMeta previous = new PipelineMeta(HopXml.parse(xml).getDocumentElement(), provider);
    Map<HopMetadataReferenceExtractor.ReferenceKey, HopProjectDefinitionIndex.MetadataReference>
        references = new LinkedHashMap<>();
    boolean[] truncated = new boolean[1];
    for (TransformMeta transform : previous.getTransforms()) {
      HopMetadataReferenceExtractor.addNative(
          transform.getTransform().getResourceMetaDataDependencies(),
          transform.getName(),
          references,
          truncated);
      HopMetadataReferenceExtractor.addAnnotatedProperties(
          transform.getTransform(), transform.getName(), TYPE_KEYS, references, truncated);
    }
    var current = extractor.extract(name + ".hpl", HopXml.parse(xml));
    List<HopProjectDefinitionIndex.MetadataReference> expected =
        new ArrayList<>(references.values());
    List<HopProjectDefinitionIndex.MetadataReference> actual =
        new ArrayList<>(current.references());
    Comparator<HopProjectDefinitionIndex.MetadataReference> order =
        Comparator.comparing(Object::toString);
    expected.sort(order);
    actual.sort(order);
    if (expected.size() != expectedCount
        || !expected.equals(actual)
        || truncated[0] != expectedTruncated
        || current.truncated() != expectedTruncated
        || actual.stream()
            .anyMatch(
                ref -> ref.source() == HopProjectDefinitionIndex.ReferenceSource.TEXT_FALLBACK)) {
      throw new IllegalStateException(
          "Native reference mismatch in "
              + name
              + ": "
              + expected
              + " != "
              + actual
              + ", truncated="
              + truncated[0]
              + "/"
              + current.truncated());
    }
    System.out.println(
        "{\"case\":\""
            + name
            + "\",\"references\":"
            + actual.size()
            + ",\"truncated\":"
            + current.truncated()
            + ",\"matched\":true}");
  }
}
