package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesBoundsTest {
  @TempDir Path temp;

  @Test
  void definitionsAndCatalogPagesAreStableAndExposeCompleteness() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    write(root, "z.hpl", "pipeline");
    write(root, "flows/a.hwf", "workflow");
    write(root, "notes/readme.txt", "notes");
    write(root, ".hop-mcp/backups/hidden.hpl", "private");
    ProjectFiles files = new ProjectFiles(root);

    Map<String, Object> definitionsFirst = files.definitionsPage(0, 1);
    Map<String, Object> definitionsFirstAgain = files.definitionsPage(0, 1);
    Map<String, Object> definitionsLast = files.definitionsPage(1, 1);
    assertEquals(definitionsFirst.get("definitions"), definitionsFirstAgain.get("definitions"));
    assertEquals("flows/a.hwf", firstPath(definitionsFirst, "definitions"));
    assertEquals("z.hpl", firstPath(definitionsLast, "definitions"));
    assertEquals(2, definitionsFirst.get("count"));
    assertEquals(1, definitionsFirst.get("returned"));
    assertEquals(true, definitionsFirst.get("count_complete"));
    assertEquals(false, definitionsFirst.get("scan_limit_reached"));
    assertEquals(true, definitionsFirst.get("results_truncated"));
    assertEquals(true, definitionsFirst.get("has_more"));
    assertEquals(false, definitionsLast.get("has_more"));
    assertEquals(false, definitionsLast.get("results_truncated"));
    assertTrue((Integer) definitionsFirst.get("visited") > 0);
    assertEquals("workflow", firstValue(definitionsFirst, "definitions", "type"));

    Map<String, Object> catalogFirst = files.catalog("**", 0, 2);
    Map<String, Object> catalogLast = files.catalog("**", 2, 2);
    assertEquals(List.of("flows/a.hwf", "notes/readme.txt"), paths(catalogFirst, "files"));
    assertEquals(List.of("z.hpl"), paths(catalogLast, "files"));
    assertEquals(3, catalogFirst.get("count"));
    assertEquals(2, catalogFirst.get("returned"));
    assertEquals(true, catalogFirst.get("count_complete"));
    assertEquals(false, catalogFirst.get("scan_limit_reached"));
    assertEquals(true, catalogFirst.get("results_truncated"));
    assertEquals(true, catalogFirst.get("has_more"));
    assertEquals(false, catalogLast.get("has_more"));
    assertTrue(((String) firstValue(catalogFirst, "files", "sha256")).matches("[0-9a-f]{64}"));
  }

  @Test
  void missingRootProducesAnIncompleteScanAndPages() throws Exception {
    Path root = Files.createDirectory(temp.resolve("disappearing"));
    ProjectFiles files = new ProjectFiles(root);
    Files.delete(root);

    BoundedProjectWalker.ScanResult scan = BoundedProjectWalker.scan(root, null, path -> true, 10);
    assertTrue(scan.files().isEmpty());
    assertTrue(scan.scanLimitReached());
    assertEquals(1, scan.visitedEntries());
    assertEquals(false, files.definitionsPage(0, 10).get("count_complete"));
    assertEquals(false, files.catalog("**", 0, 10).get("count_complete"));
    assertEquals(false, files.search("needle", "**", 0, 10).get("count_complete"));
  }

  @Test
  void walkerStopsAtVisitedFileAndDepthLimits() throws Exception {
    Path visitedRoot = Files.createDirectory(temp.resolve("visited"));
    write(visitedRoot, "a.txt", "a");
    write(visitedRoot, "b.txt", "b");
    BoundedProjectWalker.ScanResult visitedLimited =
        BoundedProjectWalker.scan(visitedRoot, null, path -> true, 10, 1, 10, 64);
    assertEquals(1, visitedLimited.visitedEntries());
    assertTrue(visitedLimited.scanLimitReached());
    assertTrue(visitedLimited.files().isEmpty());

    BoundedProjectWalker.ScanResult fileLimited =
        BoundedProjectWalker.scan(visitedRoot, null, path -> true, 1, 10, 1, 64);
    assertEquals(1, fileLimited.regularFilesExamined());
    assertEquals(1, fileLimited.files().size());
    assertTrue(fileLimited.scanLimitReached());

    Path depthRoot = Files.createDirectory(temp.resolve("depth"));
    write(depthRoot, "shallow.txt", "shallow");
    write(depthRoot, "sub/deeper.txt", "deeper");
    BoundedProjectWalker.ScanResult depthLimited =
        BoundedProjectWalker.scan(depthRoot, null, path -> true, 10, 20, 10, 1);
    assertEquals(List.of("shallow.txt"), relativePaths(depthRoot, depthLimited.files()));
    assertTrue(depthLimited.scanLimitReached());
  }

  @Test
  void walkerDoesNotFollowSymbolicLinks() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
    Path link = root.resolve("escape.txt");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Assumptions.assumeTrue(false, "symbolic links are unavailable in this environment");
      return;
    }

    BoundedProjectWalker.ScanResult scan = BoundedProjectWalker.scan(root, null, path -> true, 10);
    assertEquals(0, scan.regularFilesExamined());
    assertTrue(scan.files().isEmpty());
    assertFalse(scan.scanLimitReached());
  }

  @Test
  void definitionResultLimitDoesNotCountNonHopFilesAsDefinitions() throws Exception {
    Path root = Files.createDirectory(temp.resolve("mixed"));
    write(root, "data/a.csv", "a");
    write(root, "data/b.sql", "b");
    write(root, "notes/c.txt", "c");
    write(root, "flows/a.hpl", "pipeline");
    write(root, "flows/b.hwf", "workflow");

    BoundedProjectWalker.ScanResult scan = new ProjectFiles(root).definitionScan(2);

    assertEquals(5, scan.regularFilesExamined());
    assertEquals(List.of("flows/a.hpl", "flows/b.hwf"), relativePaths(root, scan.files()));
    assertFalse(scan.resultsTruncated());
    assertFalse(scan.scanLimitReached());
  }

  @Test
  void walkerKeepsLexicographicallyStableResultsWhileScanningPastPageLimit() throws Exception {
    Path root = Files.createDirectory(temp.resolve("stable-results"));
    write(root, "z.txt", "z");
    write(root, "a.txt", "a");
    write(root, "y.txt", "y");
    write(root, "b.txt", "b");

    BoundedProjectWalker.ScanResult result =
        BoundedProjectWalker.scan(root, null, ignored -> true, 2, 50, 50, 64);

    assertEquals(List.of("a.txt", "b.txt"), relativePaths(root, result.files()));
    assertEquals(4, result.regularFilesExamined());
    assertTrue(result.resultsTruncated());
  }

  @Test
  void walkerCarriesAttributesFromTheOriginalVisit() throws Exception {
    Path root = Files.createDirectory(temp.resolve("attributes"));
    Path file = write(root, "one.hpl", "pipeline");
    FileTime modified = FileTime.fromMillis(1_700_000_000_000L);
    Files.setLastModifiedTime(file, modified);

    BoundedProjectWalker.ScannedFile scanned =
        BoundedProjectWalker.scan(root, null, path -> true, 1).files().getFirst();

    assertEquals(Files.size(file), scanned.size());
    assertEquals(Files.getLastModifiedTime(file), scanned.lastModified());
    assertEquals(
        Files.readAttributes(file, BasicFileAttributes.class).fileKey(), scanned.fileKey());
  }

  @Test
  void textChunksKeepUtf8CodePointsWholeAndRejectInteriorOffsets() throws Exception {
    ProjectFiles files = new ProjectFiles(temp);
    String text = "A\uD83D\uDE00B\u00E9C";

    Map<String, Object> first = files.textChunk("unicode.txt", text, 0, 4);
    Map<String, Object> second = files.textChunk("unicode.txt", text, 1, 4);
    Map<String, Object> third = files.textChunk("unicode.txt", text, 5, 4);
    assertEquals("A", first.get("text"));
    assertEquals(1, first.get("returned_bytes"));
    assertEquals(true, first.get("truncated"));
    assertEquals("\uD83D\uDE00", second.get("text"));
    assertEquals(4, second.get("returned_bytes"));
    assertEquals(5L, second.get("next_offset"));
    assertEquals("B\u00E9C", third.get("text"));
    assertEquals(4, third.get("returned_bytes"));
    assertEquals(true, third.get("eof"));
    assertThrows(IllegalArgumentException.class, () -> files.textChunk("unicode.txt", text, 2, 4));
    assertThrows(IllegalArgumentException.class, () -> files.textChunk("unicode.txt", text, 10, 4));
    assertThrows(IllegalArgumentException.class, () -> files.textChunk("unicode.txt", text, -1, 4));
  }

  @Test
  void searchSkipsMalformedUtf8AndSignalsAnIncompleteScan() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    Files.write(root.resolve("a-invalid.txt"), new byte[] {(byte) 0xc3, (byte) 0x28});
    Files.writeString(root.resolve("b-match.txt"), "Needle is here\n");

    Map<String, Object> result = new ProjectFiles(root).search("needle", "**", 0, 10);
    assertEquals(1, result.get("count"));
    assertEquals(1, result.get("returned"));
    assertEquals(2, result.get("scanned_files"));
    assertEquals(false, result.get("count_complete"));
    assertEquals(true, result.get("scan_limit_reached"));
    assertEquals(true, result.get("results_truncated"));
    assertEquals(true, result.get("has_more"));
    assertEquals(List.of("b-match.txt"), paths(result, "results"));
  }

  @Test
  void catalogAndSearchStayWithinAggregateByteBudget() throws Exception {
    Path root = Files.createDirectory(temp.resolve("large-project"));
    byte[] contents = new byte[Math.toIntExact(ProjectFiles.MAX_FILE_BYTES)];
    Arrays.fill(contents, (byte) 'x');
    int fileCount = (int) (ProjectFiles.MAX_TOTAL_SCAN_BYTES / ProjectFiles.MAX_FILE_BYTES) + 1;
    for (int index = 0; index < fileCount; index++) {
      Files.write(root.resolve("file-" + index + ".txt"), contents);
    }
    ProjectFiles files = new ProjectFiles(root);

    Map<String, Object> search = files.search("needle", "**", 0, 10);
    assertEquals(ProjectFiles.MAX_TOTAL_SCAN_BYTES, search.get("scanned_bytes"));
    assertEquals(true, search.get("scan_limit_reached"));

    Map<String, Object> catalog = files.catalog("**", 0, 10);
    assertEquals(ProjectFiles.MAX_TOTAL_SCAN_BYTES, catalog.get("scanned_bytes"));
    assertEquals(true, catalog.get("scan_limit_reached"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) catalog.get("files");
    assertTrue(entries.stream().anyMatch(entry -> Boolean.TRUE.equals(entry.get("hash_skipped"))));
  }

  @Test
  void resolveRejectsEscapesAndConnectorInternalFiles() throws Exception {
    Path root = Files.createDirectory(temp.resolve("project"));
    write(root, ".hop-mcp/backups/hidden.txt", "private");
    ProjectFiles files = new ProjectFiles(root);

    McpException escape = assertThrows(McpException.class, () -> files.resolve("../outside.txt"));
    assertEquals("PATH_OUTSIDE_PROJECT", escape.code());
    McpException internal =
        assertThrows(McpException.class, () -> files.resolve(".hop-mcp/backups/hidden.txt"));
    assertEquals("INTERNAL_PATH_DENIED", internal.code());
    McpException internalWrite =
        assertThrows(
            McpException.class, () -> files.resolveForWrite(".hop-mcp/backups/hidden.txt"));
    assertEquals("INTERNAL_PATH_DENIED", internalWrite.code());
  }

  @Test
  void aliasesCannotExposeExternalOrInternalFiles() throws Exception {
    Path root = Files.createDirectory(temp.resolve("aliases"));
    Path outside = Files.createDirectory(temp.resolve("external"));
    write(outside, "outside.hpl", "external-marker");
    write(root, ".hop-mcp/backups/hidden.hpl", "protected-marker");
    ProjectFiles files = new ProjectFiles(root);
    Path externalLink = root.resolve("external-link");
    Path internalLink = root.resolve("internal-link");
    try {
      Files.createSymbolicLink(externalLink, outside);
      Files.createSymbolicLink(internalLink, root.resolve(".hop-mcp"));
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      Files.deleteIfExists(externalLink);
      Assumptions.assumeTrue(false, "symbolic links are unavailable in this environment");
      return;
    }
    try {
      assertEquals(List.of(), paths(files.catalog("**", 0, 20), "files"));
      assertEquals(List.of(), paths(files.definitionsPage(0, 20), "definitions"));
      assertEquals(0, files.search("marker", "**", 0, 20).get("count"));
      assertThrows(McpException.class, () -> files.resolve("external-link/outside.hpl"));
      McpException denied =
          assertThrows(McpException.class, () -> files.resolve("internal-link/backups/hidden.hpl"));
      assertEquals("INTERNAL_PATH_DENIED", denied.code());
      assertThrows(
          McpException.class,
          () -> files.readTextChunk("internal-link/backups/hidden.hpl", 0, 1024));
      assertThrows(IOException.class, () -> files.resolveForWrite("internal-link/backups/new.hpl"));
      assertThrows(
          IOException.class, () -> files.resolveForWrite("internal-link/backups/hidden.hpl"));
    } finally {
      Files.delete(internalLink);
      Files.delete(externalLink);
    }
  }

  @Test
  void windowsJunctionsAreExcludedAndCannotExposeBackups() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
    Path root = Files.createDirectory(temp.resolve("junction-project"));
    Path outside = Files.createDirectory(temp.resolve("junction-external"));
    write(outside, "outside.hpl", "external-junction-marker");
    write(root, ".hop-mcp/backups/hidden.hpl", "protected-junction-marker");
    Path external = root.resolve("external-junction");
    Path internal = root.resolve("internal-junction");
    createJunction(external, outside);
    try {
      createJunction(internal, root.resolve(".hop-mcp"));
      try {
        ProjectFiles files = new ProjectFiles(root);
        assertTrue(files.definitionScan(20).files().isEmpty());
        assertTrue(paths(files.catalog("**", 0, 20), "files").isEmpty());
        assertEquals(0, files.search("junction-marker", "**", 0, 20).get("count"));
        assertThrows(McpException.class, () -> files.resolve("external-junction/outside.hpl"));
        assertEquals(
            "INTERNAL_PATH_DENIED",
            assertThrows(
                    McpException.class, () -> files.resolve("internal-junction/backups/hidden.hpl"))
                .code());
        assertThrows(
            McpException.class,
            () -> files.readTextChunk("internal-junction/backups/hidden.hpl", 0, 1024));
        assertEquals(
            "INTERNAL_PATH_DENIED",
            assertThrows(
                    McpException.class,
                    () -> files.resolveForWrite("internal-junction/backups/new.hpl"))
                .code());
      } finally {
        Files.delete(internal);
      }
    } finally {
      Files.delete(external);
    }
  }

  @Test
  void explicitResolutionOfOrdinaryInternalAliasRemainsAllowed() throws Exception {
    Path root = Files.createDirectory(temp.resolve("allowed-project"));
    Path destination = Files.createDirectory(root.resolve("ordinary"));
    Path original = write(root, "ordinary/data.txt", "ordinary-data");
    Path alias = root.resolve("alias");
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    if (windows) {
      createJunction(alias, destination);
    } else {
      Files.createSymbolicLink(alias, destination);
    }
    try {
      ProjectFiles files = new ProjectFiles(root);
      assertEquals(original.toRealPath(), files.resolve("alias/data.txt"));
      assertEquals("ordinary-data", files.readText("alias/data.txt"));
      assertEquals(List.of("ordinary/data.txt"), paths(files.catalog("**", 0, 20), "files"));
      if (windows) {
        assertEquals(destination.resolve("new.txt"), files.resolveForWrite("alias/new.txt"));
      }
    } finally {
      Files.delete(alias);
    }
  }

  private static void createJunction(Path link, Path target) throws Exception {
    Process process =
        new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), output);
  }

  private static Path write(Path root, String relative, String content) throws IOException {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
    return path;
  }

  @SuppressWarnings("unchecked")
  private static List<String> paths(Map<String, Object> result, String field) {
    return ((List<Map<String, Object>>) result.get(field))
        .stream().map(item -> (String) item.get("path")).toList();
  }

  @SuppressWarnings("unchecked")
  private static String firstPath(Map<String, Object> result, String field) {
    return (String) ((List<Map<String, Object>>) result.get(field)).getFirst().get("path");
  }

  @SuppressWarnings("unchecked")
  private static Object firstValue(Map<String, Object> result, String field, String key) {
    return ((List<Map<String, Object>>) result.get(field)).getFirst().get(key);
  }

  private static List<String> relativePaths(
      Path root, List<BoundedProjectWalker.ScannedFile> paths) {
    return paths.stream()
        .map(BoundedProjectWalker.ScannedFile::path)
        .map(path -> root.relativize(path).toString().replace('\\', '/'))
        .toList();
  }
}
