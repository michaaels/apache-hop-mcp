package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.hop.core.Const;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.variables.Variables;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HopMcpCommandTest {
  @TempDir Path project;

  @BeforeAll
  static void initializeHopPlugins() throws Exception {
    HopEnvironment.init();
  }

  @Test
  @Timeout(15)
  void lateWorkerLogsStayOnStderrAfterStdioEof() throws Exception {
    assertLateLogsAreIsolated(false);
  }

  @Test
  @Timeout(15)
  void lateWorkerLogsStayOnStderrAfterStartupFailure() throws Exception {
    assertLateLogsAreIsolated(true);
  }

  private void assertLateLogsAreIsolated(boolean startupFailure) throws Exception {
    HopMcpCommand command = new HopMcpCommand();
    CommandLine cli = new CommandLine(command);
    command.initialize(cli, new Variables(), null);
    Path root =
        startupFailure ? Files.writeString(project.resolve("not-a-directory"), "x") : project;
    cli.parseArgs("--root", root.toString());

    InputStream previousIn = System.in;
    PrintStream previousOut = System.out;
    PrintStream previousErr = System.err;
    PrintStream previousHopOut = HopLogStore.OriginalSystemOut;
    PrintStream previousHopErr = HopLogStore.OriginalSystemErr;
    String previousRuntime = System.getProperty(Const.HOP_PLATFORM_RUNTIME);
    ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
    ByteArrayOutputStream protocol = new ByteArrayOutputStream();
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    try (PrintStream protocolOut = new PrintStream(protocol, true, StandardCharsets.UTF_8);
        PrintStream hopOut = new PrintStream(protocol, true, StandardCharsets.UTF_8);
        PrintStream stderr = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
      System.setIn(new ByteArrayInputStream(new byte[0]));
      System.setOut(protocolOut);
      System.setErr(stderr);
      HopLogStore.OriginalSystemOut = hopOut;
      HopLogStore.OriginalSystemErr = stderr;

      if (startupFailure) assertThrows(RuntimeException.class, command::run);
      else command.run();
      assertSame(previousLoader, Thread.currentThread().getContextClassLoader());

      // Force the ordering that timed-out native pipelines can produce: the command
      // has returned, but a worker still emits its final log and console output.
      Thread lateWorker =
          new Thread(
              () -> {
                new LogChannel("late-native-worker").logBasic("Pipeline duration after timeout");
                System.out.println("late-plugin-console-output");
              },
              "late-hop-worker");
      lateWorker.setDaemon(true);
      lateWorker.start();
      lateWorker.join(5000);
      assertFalse(lateWorker.isAlive(), "Late logging must not block");
      assertEquals("", protocol.toString(StandardCharsets.UTF_8));
      String logs = diagnostics.toString(StandardCharsets.UTF_8);
      assertTrue(logs.contains("Pipeline duration after timeout"), logs);
      assertTrue(logs.contains("late-plugin-console-output"), logs);
    } finally {
      // Only the test harness restores process streams. The CLI owns them until exit.
      System.setIn(previousIn);
      System.setOut(previousOut);
      System.setErr(previousErr);
      HopLogStore.OriginalSystemOut = previousHopOut;
      HopLogStore.OriginalSystemErr = previousHopErr;
      if (previousRuntime == null) System.clearProperty(Const.HOP_PLATFORM_RUNTIME);
      else System.setProperty(Const.HOP_PLATFORM_RUNTIME, previousRuntime);
    }
  }
}
