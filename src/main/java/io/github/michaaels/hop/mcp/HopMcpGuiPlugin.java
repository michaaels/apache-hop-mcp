package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.commons.lang3.StringUtils;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.menu.GuiMenuElement;
import org.apache.hop.ui.hopgui.HopGui;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;

/** Hop Desktop entry point for explicit, session-scoped MCP live synchronization. */
@GuiPlugin
public class HopMcpGuiPlugin {
  public static final String MENU_ID = "40250-menu-tools-apache-hop-mcp";

  private static HopDesktopLiveSync liveSync;

  @GuiMenuElement(
      root = HopGui.ID_MAIN_MENU,
      id = MENU_ID,
      label = "Apache Hop MCP live synchronization...",
      toolTip = "Start or stop live synchronization for semantic MCP changes",
      parentId = HopGui.ID_MAIN_MENU_TOOLS_PARENT_ID,
      separator = true)
  public void toggleLiveSynchronization() {
    HopGui hopGui = HopGui.getInstance();
    synchronized (HopMcpGuiPlugin.class) {
      if (liveSync != null && liveSync.isRunning()) {
        stopLiveSynchronization(hopGui);
      } else {
        startLiveSynchronization(hopGui);
      }
    }
  }

  static synchronized boolean isLiveSynchronizationRunning() {
    return liveSync != null && liveSync.isRunning();
  }

  private static void startLiveSynchronization(HopGui hopGui) {
    try {
      Path projectRoot = determineProjectRoot(hopGui);
      HopDesktopLiveSync candidate = new HopDesktopLiveSync(hopGui, projectRoot);
      candidate.start();
      liveSync = candidate;
      showInformation(
          hopGui,
          "Apache Hop MCP live synchronization",
          "Live synchronization is running for:\n\n"
              + projectRoot
              + "\n\nSemantic MCP changes will open or refresh Hop definitions. "
              + "Tabs with unsaved changes are never overwritten.");
    } catch (Exception e) {
      hopGui.getLog().logError("Unable to start Apache Hop MCP live synchronization", e);
      showError(
          hopGui,
          "Apache Hop MCP",
          "Live synchronization could not be started. See the Hop log for details.");
    }
  }

  private static void stopLiveSynchronization(HopGui hopGui) {
    try {
      liveSync.close();
      liveSync = null;
      showInformation(
          hopGui, "Apache Hop MCP live synchronization", "Live synchronization has stopped.");
    } catch (IOException e) {
      hopGui.getLog().logError("Unable to stop Apache Hop MCP live synchronization", e);
      showError(
          hopGui,
          "Apache Hop MCP",
          "Live synchronization could not be stopped cleanly. See the Hop log for details.");
    }
  }

  private static Path determineProjectRoot(HopGui hopGui) {
    String configured = hopGui.getVariables().getVariable("PROJECT_HOME");
    if (StringUtils.isBlank(configured)) {
      configured = System.getProperty("user.dir");
    } else {
      configured = hopGui.getVariables().resolve(configured);
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private static void showInformation(HopGui hopGui, String title, String message) {
    MessageBox box = new MessageBox(hopGui.getShell(), SWT.OK | SWT.ICON_INFORMATION);
    box.setText(title);
    box.setMessage(message);
    box.open();
  }

  private static void showError(HopGui hopGui, String title, String message) {
    MessageBox box = new MessageBox(hopGui.getShell(), SWT.OK | SWT.ICON_ERROR);
    box.setText(title);
    box.setMessage(message);
    box.open();
  }
}
