package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ProjectFiles {
  static final long MAX_READ_BYTES = 4L * 1024 * 1024;
  static final int MAX_SCAN_FILES = 5000;
  static final int MAX_RESULTS = 500;
  private final Path root;
  ProjectFiles(Path root) throws IOException { Path absolute = root.toAbsolutePath().normalize(); if (!Files.isDirectory(absolute)) throw new IOException("Project root is not a directory: " + absolute); this.root = absolute.toRealPath(); }
  Path root() { return root; }
  Path resolve(String relative) throws IOException { if (relative == null || relative.isBlank()) throw new IOException("path is required"); Path candidate = root.resolve(relative).normalize(); if (!candidate.startsWith(root)) throw new IOException("Path escapes project root"); if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Path not found: " + relative); Path real = candidate.toRealPath(); if (!real.startsWith(root)) throw new IOException("Resolved path escapes project root"); return real; }
  String relative(Path p) { return root.relativize(p).toString().replace('\\', '/'); }
  String readText(String relative) throws IOException { Path p = resolve(relative); if (!Files.isRegularFile(p)) throw new IOException("Not a regular file: " + relative); long size = Files.size(p); if (size > MAX_READ_BYTES) throw new IOException("File exceeds read limit: " + size + " bytes"); return Files.readString(p, StandardCharsets.UTF_8); }
  List<Path> definitions() throws IOException { try (Stream<Path> s = Files.walk(root)) { return s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).filter(p -> { String n = p.getFileName().toString().toLowerCase(Locale.ROOT); return n.endsWith(".hpl") || n.endsWith(".hwf"); }).limit(MAX_SCAN_FILES).sorted(Comparator.comparing(this::relative)).toList(); } }

  Map<String,Object> catalog(String glob, int offset, int limit) throws IOException {
    if (offset < 0 || offset > MAX_SCAN_FILES) throw new IllegalArgumentException("offset must be between 0 and " + MAX_SCAN_FILES);
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    Pattern filter = globToPattern(glob == null || glob.isBlank() ? "**" : glob);
    List<Path> scannedPaths;
    try (Stream<Path> stream = Files.walk(root)) {
      scannedPaths = new ArrayList<>(stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).limit(MAX_SCAN_FILES + 1L).toList());
    }
    boolean scanLimitReached = scannedPaths.size() > MAX_SCAN_FILES;
    if (scanLimitReached) scannedPaths = new ArrayList<>(scannedPaths.subList(0, MAX_SCAN_FILES));
    scannedPaths.sort(Comparator.comparing(this::relative));
    List<Path> matches = scannedPaths.stream().filter(path -> filter.matcher(relative(path)).matches()).toList();
    int from = Math.min(offset, matches.size());
    int to = Math.min(from + limit, matches.size());
    List<Map<String,Object>> entries = new ArrayList<>();
    for (Path path : matches.subList(from, to)) {
      Map<String,Object> entry = new LinkedHashMap<>();
      String relative = relative(path);
      long size = Files.size(path);
      entry.put("path", relative);
      entry.put("kind", fileKind(relative));
      entry.put("extension", extension(relative));
      entry.put("bytes", size);
      entry.put("last_modified_epoch_ms", Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis());
      if (size <= MAX_READ_BYTES) {
        entry.put("sha256", sha256(Files.readAllBytes(path)));
        entry.put("hash_skipped", false);
      } else {
        entry.put("sha256", "");
        entry.put("hash_skipped", true);
        entry.put("hash_skip_reason", "file exceeds read limit");
      }
      entries.add(entry);
    }
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("glob", glob == null || glob.isBlank() ? "**" : glob);
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("scanned", scannedPaths.size());
    result.put("scan_limit_reached", scanLimitReached);
    result.put("count", matches.size());
    result.put("returned", entries.size());
    result.put("has_more", to < matches.size() || scanLimitReached);
    result.put("files", entries);
    return result;
  }
  List<Map<String,Object>> search(String query, String glob) throws IOException {
    if (query == null || query.isBlank()) throw new IOException("query is required"); String needle = query.toLowerCase(Locale.ROOT); Pattern filter = globToPattern(glob == null || glob.isBlank() ? "**" : glob); List<Map<String,Object>> out = new ArrayList<>(); int[] scanned = {0};
    try (Stream<Path> s = Files.walk(root)) { for (Path p : (Iterable<Path>) s.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))::iterator) { if (++scanned[0] > MAX_SCAN_FILES || out.size() >= MAX_RESULTS) break; String rel = relative(p); if (!filter.matcher(rel).matches()) continue; long size; try { size = Files.size(p); } catch (IOException e) { continue; } if (size > MAX_READ_BYTES) continue; List<String> lines; try { lines = Files.readAllLines(p, StandardCharsets.UTF_8); } catch (Exception e) { continue; } for (int i=0; i<lines.size() && out.size()<MAX_RESULTS; i++) { if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) out.add(Map.of("path", rel, "line", i+1, "text", truncate(lines.get(i), 500))); } } }
    return out;
  }
  Path resolveForWrite(String relative) throws IOException {
    if (relative == null || relative.isBlank()) throw new IOException("path is required");
    Path candidate = root.resolve(relative).normalize();
    if (!candidate.startsWith(root)) throw new IOException("Path escapes project root");
    Path parent = candidate.getParent();
    if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Parent directory not found: " + relative);
    Path realParent = parent.toRealPath();
    if (!realParent.startsWith(root)) throw new IOException("Parent directory escapes project root");
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + relative);
      Path real = candidate.toRealPath();
      if (!real.startsWith(root)) throw new IOException("Resolved path escapes project root");
    }
    return candidate;
  }
  byte[] readBytes(Path path) throws IOException { long size=Files.size(path); if(size>MAX_READ_BYTES)throw new IOException("File exceeds read limit: "+size+" bytes"); return Files.readAllBytes(path); }
  static String sha256(byte[] bytes) throws IOException { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new IOException("SHA-256 is unavailable",e); } }
  private static String extension(String path) { int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT); }
  private static String fileKind(String path) { return switch (extension(path)) { case "hpl" -> "pipeline"; case "hwf" -> "workflow"; case "ktr" -> "kettle_pipeline"; case "kjb" -> "kettle_workflow"; case "csv" -> "csv"; case "json", "xml", "yaml", "yml", "properties" -> "metadata"; case "txt", "sql", "md", "log" -> "text"; default -> "file"; }; }
  private static Pattern globToPattern(String glob) { StringBuilder r = new StringBuilder("^"); for (int i=0;i<glob.length();i++) { char c=glob.charAt(i); if (c=='*') { if (i+1<glob.length() && glob.charAt(i+1)=='*') { r.append(".*"); i++; } else r.append("[^/]*"); } else if (c=='?') r.append('.'); else if ("\\.[]{}()+-^$|".indexOf(c)>=0) r.append('\\').append(c); else r.append(c=='\\' ? '/' : c); } return Pattern.compile(r.append('$').toString(), Pattern.CASE_INSENSITIVE); }
  static String truncate(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0,max) + "…"; }
}
