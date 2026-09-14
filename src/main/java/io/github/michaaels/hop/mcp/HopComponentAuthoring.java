package io.github.michaaels.hop.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.hop.core.injection.bean.BeanInjectionInfo;
import org.apache.hop.core.injection.bean.BeanInjector;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.action.IAction;

/** Discovers and creates Hop components using the native plugin and metadata-injection APIs. */
final class HopComponentAuthoring {
  static final int MAX_PROPERTIES = 50;
  static final int MAX_PROPERTY_VALUE_LENGTH = 8_192;
  static final int MAX_COMPONENT_NAME_LENGTH = 200;
  static final int MAX_PLUGIN_ID_LENGTH = 200;

  private static final Pattern SENSITIVE_KEY =
      Pattern.compile(
          "(?i)(password|passwd|secret|token|credential|private[_.-]?key|access[_.-]?key|client[_.-]?secret|authorization|auth[_.-]?header)");
  private static final Set<Class<?>> SCALAR_TYPES =
      Set.of(
          String.class,
          Boolean.class,
          Byte.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          Character.class,
          BigDecimal.class,
          BigInteger.class);
  private static final Set<String> RESERVED_PROPERTIES =
      Set.of("name", "type", "pluginid", "plugin_id");

  private final PluginRegistry registry;
  private final IHopMetadataProvider metadataProvider;

  HopComponentAuthoring(IHopMetadataProvider metadataProvider) {
    this(PluginRegistry.getInstance(), metadataProvider);
  }

  HopComponentAuthoring(PluginRegistry registry, IHopMetadataProvider metadataProvider) {
    this.registry = registry;
    this.metadataProvider = metadataProvider;
  }

  Map<String, Object> types(String requestedKind, String query, int offset, int limit) {
    Kind kind = Kind.parse(requestedKind);
    if (offset < 0) throw new IllegalArgumentException("offset must be zero or greater");
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("limit must be between 1 and 50");
    String normalizedQuery = normalize(query);
    List<IPlugin> matches =
        registry.getPlugins(kind.pluginType).stream()
            .filter(plugin -> matches(plugin, normalizedQuery))
            .sorted(
                Comparator.comparing(
                        (IPlugin plugin) -> safe(plugin.getName()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(HopComponentAuthoring::canonicalId))
            .toList();
    List<Map<String, Object>> components = new ArrayList<>();
    for (int i = offset; i < matches.size() && components.size() < limit; i++) {
      components.add(pluginRow(matches.get(i)));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind.value);
    result.put("matched_component_count", matches.size());
    result.put("returned_component_count", components.size());
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("has_more", offset + components.size() < matches.size());
    if (normalizedQuery != null) result.put("query", query);
    result.put("components", components);
    return result;
  }

  Map<String, Object> schema(String requestedKind, String pluginId) throws Exception {
    Kind kind = Kind.parse(requestedKind);
    IPlugin plugin = requirePlugin(kind, pluginId);
    Object component = instantiate(kind, plugin);
    List<Map<String, Object>> properties = injectableProperties(component);
    boolean nativeInjectionSupported = BeanInjectionInfo.isInjectionSupported(component.getClass());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind.value);
    result.put("plugin", pluginRow(plugin));
    result.put("native_injection_supported", nativeInjectionSupported);
    result.put("scalar_injection_supported", !properties.isEmpty());
    result.put("property_count", properties.size());
    result.put("properties", properties);
    result.put("sensitive_properties_excluded", true);
    result.put("collection_properties_excluded", true);
    result.put("structural_properties_excluded", true);
    return result;
  }

  TransformMeta createTransform(
      String pluginId, String componentName, Object rawProperties, int x, int y) throws Exception {
    Kind kind = Kind.PIPELINE;
    IPlugin plugin = requirePlugin(kind, pluginId);
    ITransformMeta transform = (ITransformMeta) instantiate(kind, plugin);
    transform.setDefault();
    inject(transform, rawProperties);
    TransformMeta result =
        new TransformMeta(canonicalId(plugin), validName(componentName), transform);
    result.setLocation(x, y);
    return result;
  }

  ActionMeta createAction(
      WorkflowMeta workflowMeta,
      String pluginId,
      String componentName,
      Object rawProperties,
      int x,
      int y)
      throws Exception {
    Kind kind = Kind.WORKFLOW;
    IPlugin plugin = requirePlugin(kind, pluginId);
    IAction action = (IAction) instantiate(kind, plugin);
    action.setPluginId(canonicalId(plugin));
    action.setName(validName(componentName));
    action.setMetadataProvider(metadataProvider);
    action.setParentWorkflowMeta(workflowMeta);
    if (action.isStart() && workflowMeta.findStart() != null) {
      throw new IllegalArgumentException("A workflow can contain only one Start action");
    }
    inject(action, rawProperties);
    ActionMeta result = new ActionMeta(action);
    result.setLocation(x, y);
    result.setParentWorkflowMeta(workflowMeta);
    return result;
  }

  private Object instantiate(Kind kind, IPlugin plugin) throws Exception {
    Object component = registry.loadClass(plugin);
    if (!kind.componentType.isInstance(component)) {
      throw new IllegalStateException(
          "Plugin "
              + canonicalId(plugin)
              + " did not create a "
              + kind.componentType.getSimpleName());
    }
    return component;
  }

  private IPlugin requirePlugin(Kind kind, String pluginId) {
    String validId = validPluginId(pluginId);
    IPlugin plugin = registry.findPluginWithId(kind.pluginType, validId);
    if (plugin == null) {
      throw new IllegalArgumentException("Unknown " + kind.value + " component plugin: " + validId);
    }
    return plugin;
  }

  private List<Map<String, Object>> injectableProperties(Object component) {
    if (!BeanInjectionInfo.isInjectionSupported(component.getClass())) return List.of();
    BeanInjectionInfo<?> info = injectionInfo(component);
    return info.getProperties().values().stream()
        .filter(this::isPublicScalarProperty)
        .sorted(Comparator.comparing(BeanInjectionInfo.Property::getKey))
        .map(this::propertyRow)
        .toList();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void inject(Object component, Object rawProperties) throws Exception {
    Map<String, Object> properties = validatedProperties(rawProperties);
    if (properties.isEmpty()) return;
    for (String key : properties.keySet()) {
      if (isSensitive(key))
        throw new SecurityException("Sensitive component properties are not accepted");
    }
    if (!BeanInjectionInfo.isInjectionSupported(component.getClass())) {
      throw new IllegalArgumentException(
          "Plugin does not expose injectable scalar properties: " + component.getClass().getName());
    }
    BeanInjectionInfo info = injectionInfo(component);
    BeanInjector injector = new BeanInjector(info, metadataProvider);
    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      String key = entry.getKey();
      BeanInjectionInfo.Property property =
          (BeanInjectionInfo.Property) info.getProperties().get(key);
      if (property == null || !isPublicScalarProperty(property)) {
        throw new IllegalArgumentException("Unknown or non-scalar injectable property: " + key);
      }
      injector.setProperty(component, key, null, String.valueOf(entry.getValue()));
    }
    injector.runPostInjectionProcessing(component);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static BeanInjectionInfo<?> injectionInfo(Object component) {
    return new BeanInjectionInfo((Class) component.getClass());
  }

  private boolean isPublicScalarProperty(BeanInjectionInfo<?>.Property property) {
    return property.pathArraysCount == 0
        && (property.getGroupKey() == null || property.getGroupKey().isBlank())
        && !property.isExcludedFromInjection()
        && !isReserved(property.getKey())
        && !isSensitive(property.getKey())
        && isScalar(property.getPropertyClass());
  }

  private Map<String, Object> propertyRow(BeanInjectionInfo<?>.Property property) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("key", property.getKey());
    row.put("description", safe(property.getTranslatedDescription()));
    row.put("java_type", property.getPropertyClass().getName());
    row.put("value_type", valueType(property.getPropertyClass()));
    if (property.getPropertyClass().isEnum()) {
      List<String> values = new ArrayList<>();
      for (Object value : property.getPropertyClass().getEnumConstants()) {
        values.add(String.valueOf(value));
      }
      row.put("allowed_values", values);
    }
    return row;
  }

  private static Map<String, Object> validatedProperties(Object value) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException("properties must be an object");
    if (map.size() > MAX_PROPERTIES)
      throw new IllegalArgumentException("properties cannot exceed " + MAX_PROPERTIES + " entries");
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
      if (key.isBlank() || key.length() > MAX_PLUGIN_ID_LENGTH)
        throw new IllegalArgumentException("property names must contain 1 to 200 characters");
      Object propertyValue = entry.getValue();
      if (!(propertyValue instanceof String
          || propertyValue instanceof Number
          || propertyValue instanceof Boolean)) {
        throw new IllegalArgumentException("property values must be strings, numbers or booleans");
      }
      if (String.valueOf(propertyValue).length() > MAX_PROPERTY_VALUE_LENGTH)
        throw new IllegalArgumentException(
            "property values cannot exceed " + MAX_PROPERTY_VALUE_LENGTH + " characters");
      result.put(key, propertyValue);
    }
    return result;
  }

  private static boolean isScalar(Class<?> type) {
    return type.isPrimitive() || type.isEnum() || SCALAR_TYPES.contains(type);
  }

  private static String valueType(Class<?> type) {
    if (type == boolean.class || type == Boolean.class) return "boolean";
    if ((type.isPrimitive() && type != char.class) || Number.class.isAssignableFrom(type))
      return "number";
    return "string";
  }

  private static boolean matches(IPlugin plugin, String query) {
    if (query == null) return true;
    if (contains(plugin.getName(), query)
        || contains(plugin.getDescription(), query)
        || contains(plugin.getCategory(), query)) return true;
    if (plugin.getIds() != null) {
      for (String id : plugin.getIds()) if (contains(id, query)) return true;
    }
    return false;
  }

  private static boolean contains(Object value, String query) {
    return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(query);
  }

  private static Map<String, Object> pluginRow(IPlugin plugin) {
    return Map.of(
        "id", canonicalId(plugin),
        "ids", List.of(plugin.getIds()),
        "name", safe(plugin.getName()),
        "description", safe(plugin.getDescription()),
        "category", safe(plugin.getCategory()));
  }

  private static String canonicalId(IPlugin plugin) {
    if (plugin.getIds() == null || plugin.getIds().length == 0 || plugin.getIds()[0].isBlank()) {
      throw new IllegalStateException("Hop plugin has no canonical ID");
    }
    return plugin.getIds()[0];
  }

  private static String validPluginId(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("plugin_id is required");
    if (value.length() > MAX_PLUGIN_ID_LENGTH)
      throw new IllegalArgumentException(
          "plugin_id cannot exceed " + MAX_PLUGIN_ID_LENGTH + " characters");
    return value;
  }

  private static String validName(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("name is required");
    if (value.length() > MAX_COMPONENT_NAME_LENGTH)
      throw new IllegalArgumentException(
          "name cannot exceed " + MAX_COMPONENT_NAME_LENGTH + " characters");
    return value;
  }

  private static boolean isSensitive(String key) {
    return key != null && SENSITIVE_KEY.matcher(key).find();
  }

  private static boolean isReserved(String key) {
    return key != null && RESERVED_PROPERTIES.contains(key.toLowerCase(Locale.ROOT));
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
  }

  private static String safe(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private enum Kind {
    PIPELINE("pipeline", TransformPluginType.class, ITransformMeta.class),
    WORKFLOW("workflow", ActionPluginType.class, IAction.class);

    private final String value;
    private final Class<? extends IPluginType> pluginType;
    private final Class<?> componentType;

    Kind(String value, Class<? extends IPluginType> pluginType, Class<?> componentType) {
      this.value = value;
      this.pluginType = pluginType;
      this.componentType = componentType;
    }

    private static Kind parse(String value) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException("kind is required");
      return switch (value.toLowerCase(Locale.ROOT)) {
        case "pipeline" -> PIPELINE;
        case "workflow" -> WORKFLOW;
        default -> throw new IllegalArgumentException("kind must be pipeline or workflow");
      };
    }
  }
}
