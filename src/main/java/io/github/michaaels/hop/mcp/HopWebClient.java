package io.github.michaaels.hop.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A bounded, read-only client for a configured Apache Hop Web base URL. */
final class HopWebClient {
  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private static final int MAX_HEADERS = 32;
  private static final Set<String> ALLOWED_METHODS = Set.of("GET", "HEAD");
  private static final Set<String> BLOCKED_HEADERS =
      Set.of("authorization", "cookie", "host", "content-length", "proxy-authorization");
  private static final Set<String> SENSITIVE_RESPONSE_HEADERS =
      Set.of("set-cookie", "authorization", "proxy-authenticate", "www-authenticate");

  private final URI baseUri;
  private final String username;
  private final String password;
  private final String bearerToken;
  private final Duration timeout;
  private final HttpClient client;

  HopWebClient(
      String baseUrl, String username, String password, String bearerToken, int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > 120) {
      throw new IllegalArgumentException("web timeout must be between 1 and 120 seconds");
    }
    this.baseUri = normalizeBaseUri(baseUrl);
    this.username = blankToNull(username);
    this.password = blankToNull(password);
    this.bearerToken = blankToNull(bearerToken);
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  Map<String, Object> request(String method, String path, Map<String, String> headers)
      throws IOException, InterruptedException {
    String requestMethod = normalizeMethod(method);
    URI uri = resolve(path);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .method(requestMethod, HttpRequest.BodyPublishers.noBody());

    int headerCount = 0;
    if (headers != null) {
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        if (++headerCount > MAX_HEADERS) {
          throw new IllegalArgumentException("REST request has too many headers");
        }
        String name = entry.getKey();
        if (name == null
            || name.isBlank()
            || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
          throw new IllegalArgumentException("REST request contains a forbidden header");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException("REST request contains a null header value");
        }
        builder.header(name, entry.getValue());
      }
    }
    addAuthentication(builder);

    HttpResponse<InputStream> response =
        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream responseStream = response.body()) {
      ReadResult body = readResponse(responseStream);
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("method", requestMethod);
      output.put("url", publicUrl(uri));
      output.put("query_present", uri.getRawQuery() != null);
      output.put("status", response.statusCode());
      output.put("ok", response.statusCode() >= 200 && response.statusCode() < 300);
      output.put("headers", responseHeaders(response.headers().map()));
      output.put("body", HopXml.redact(body.text()));
      output.put("body_bytes", body.bytes());
      output.put("body_truncated", body.truncated());
      return output;
    }
  }

  String baseUrl() {
    return baseUri.toString();
  }

  private void addAuthentication(HttpRequest.Builder builder) {
    if (bearerToken != null) {
      builder.header("Authorization", "Bearer " + bearerToken);
    } else if (username != null) {
      String credentials = username + ":" + (password == null ? "" : password);
      String encoded =
          Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
      builder.header("Authorization", "Basic " + encoded);
    }
  }

  private URI resolve(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("REST path is required");
    }
    URI requested;
    try {
      requested = URI.create(path.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("REST path is not a valid URI", e);
    }
    if (requested.isAbsolute() || requested.getFragment() != null) {
      throw new IllegalArgumentException(
          "REST path must be relative and cannot contain a fragment");
    }
    String requestedPath = requested.getPath();
    if (requestedPath == null || requestedPath.isBlank()) {
      requestedPath = "/";
    }
    if (requestedPath.toLowerCase(Locale.ROOT).contains("%2e")) {
      throw new IllegalArgumentException("REST path contains an encoded traversal segment");
    }
    String basePath = baseUri.getPath();
    String suffix = requestedPath.startsWith("/") ? requestedPath.substring(1) : requestedPath;
    String joined = "/".equals(basePath) ? "/" + suffix : basePath + "/" + suffix;
    URI normalized;
    try {
      normalized =
          new URI(
                  baseUri.getScheme(),
                  baseUri.getRawAuthority(),
                  joined,
                  requested.getRawQuery(),
                  null)
              .normalize();
    } catch (Exception e) {
      throw new IllegalArgumentException("REST path is not a valid URI", e);
    }
    if (!isInsideBasePath(normalized.getPath(), basePath)) {
      throw new IllegalArgumentException("REST path escapes the configured Hop Web base path");
    }
    return normalized;
  }

  private static URI normalizeBaseUri(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("Hop Web URL is required");
    }
    URI uri;
    try {
      uri = URI.create(baseUrl.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Hop Web URL is not a valid URI", e);
    }
    String scheme = uri.getScheme();
    if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException(
          "Hop Web URL must be an http(s) URL without credentials, query or fragment");
    }
    String path = uri.getPath();
    if (path == null || path.isBlank()) {
      path = "/";
    }
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    try {
      return new URI(
          uri.getScheme().toLowerCase(Locale.ROOT), uri.getRawAuthority(), path, null, null);
    } catch (Exception e) {
      throw new IllegalArgumentException("Hop Web URL is not a valid URI", e);
    }
  }

  private static boolean isInsideBasePath(String path, String basePath) {
    return "/".equals(basePath)
        ? path.startsWith("/")
        : path.equals(basePath) || path.startsWith(basePath + "/");
  }

  private static String normalizeMethod(String method) {
    String value = method == null || method.isBlank() ? "GET" : method.toUpperCase(Locale.ROOT);
    if (!ALLOWED_METHODS.contains(value)) {
      throw new SecurityException("Only GET and HEAD Hop Web requests are allowed");
    }
    return value;
  }

  private static Map<String, Object> responseHeaders(Map<String, List<String>> headers) {
    Map<String, Object> output = new LinkedHashMap<>();
    int count = 0;
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (++count > MAX_HEADERS) {
        break;
      }
      String key = entry.getKey();
      if (key == null) {
        continue;
      }
      String lower = key.toLowerCase(Locale.ROOT);
      output.put(
          key,
          isSensitiveResponseHeader(lower)
              ? "[REDACTED]"
              : HopXml.redact(String.join(", ", entry.getValue())));
    }
    return output;
  }

  private static boolean isSensitiveResponseHeader(String lower) {
    String normalized = lower.replace("-", "").replace("_", "");
    return SENSITIVE_RESPONSE_HEADERS.contains(lower)
        || normalized.contains("token")
        || normalized.contains("secret")
        || normalized.contains("password")
        || normalized.contains("passwd")
        || normalized.contains("apikey")
        || normalized.contains("accesskey")
        || normalized.contains("privatekey");
  }

  private static ReadResult readResponse(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int total = 0;
    while (total < MAX_RESPONSE_BYTES) {
      int count = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES - total));
      if (count < 0) {
        return new ReadResult(output.toString(StandardCharsets.UTF_8), total, false);
      }
      output.write(buffer, 0, count);
      total += count;
    }
    // Stop reading immediately at the bound. This may report truncation when a response is exactly
    // 4 MiB, but it prevents an unbounded or slow response from holding the MCP request open.
    return new ReadResult(output.toString(StandardCharsets.UTF_8), total, true);
  }

  private static String publicUrl(URI uri) {
    try {
      return new URI(uri.getScheme(), uri.getRawAuthority(), uri.getPath(), null, null).toString();
    } catch (Exception e) {
      return baseOnly(uri);
    }
  }

  private static String baseOnly(URI uri) {
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record ReadResult(String text, int bytes, boolean truncated) {}
}
