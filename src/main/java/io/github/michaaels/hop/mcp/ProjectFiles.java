package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
  List<Path> definitions() throws IOException { try (Stream<Path> s = Files.walk(root)) { return s.filter(Files::isRegularFile).filter(p -> { String n = p.getFileName().toString().toLowerCase(Locale.ROOT); return n.endsWith(".hpl") || n.endsWith(".hwf"); }).limit(MAX_SCAN_FILES).sorted(Comparator.comparing(this::relative)).toList(); } }
  List<Map<String,Object>> search(String query, String glob) throws IOException {
    if (query == null || query.isBlank()) throw new IOException("query is required"); String needle = query.toLowerCase(Locale.ROOT); Pattern filter = globToPattern(glob == null || glob.isBlank() ? "*" : glob); List<Map<String,Object>> out = new ArrayList<>(); int[] scanned = {0};
    try (Stream<Path> s = Files.walk(root)) { for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) { if (++scanned[0] > MAX_SCAN_FILES || out.size() >= MAX_RESULTS) break; String rel = relative(p); if (!filter.matcher(rel).matches()) continue; long size; try { size = Files.size(p); } catch (IOException e) { continue; } if (size > MAX_READ_BYTES) continue; List<String> lines; try { lines = Files.readAllLines(p, StandardCharsets.UTF_8); } catch (Exception e) { continue; } for (int i=0; i<lines.size() && out.size()<MAX_RESULTS; i++) { if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) out.add(Map.of("path", rel, "line", i+1, "text", truncate(lines.get(i), 500))); } } }
    return out;
  }
  private static Pattern globToPattern(String glob) { StringBuilder r = new StringBuilder("^"); for (int i=0;i<glob.length();i++) { char c=glob.charAt(i); if (c=='*') { if (i+1<glob.length() && glob.charAt(i+1)=='*') { r.append(".*"); i++; } else r.append("[^/]*"); } else if (c=='?') r.append('.'); else if ("\\.[]{}()+-^$|".indexOf(c)>=0) r.append('\\').append(c); else r.append(c=='\\' ? '/' : c); } return Pattern.compile(r.append('$').toString(), Pattern.CASE_INSENSITIVE); }
  static String truncate(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0,max) + "…"; }
}
