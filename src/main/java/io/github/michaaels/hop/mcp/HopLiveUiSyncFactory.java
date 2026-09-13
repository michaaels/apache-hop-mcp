package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.util.EnvironmentUtils;

/** Selects the visual adapter without exposing RAP implementation classes to the plugin. */
final class HopLiveUiSyncFactory {
  private HopLiveUiSyncFactory() {}

  static HopLiveUiSync create(HopGui hopGui, Path projectRoot, Runnable closedCallback)
      throws IOException {
    return create(hopGui, projectRoot, closedCallback, EnvironmentUtils.getInstance().isWeb());
  }

  static HopLiveUiSync create(HopGui hopGui, Path projectRoot, Runnable closedCallback, boolean web)
      throws IOException {
    return web
        ? new HopWebLiveSync(hopGui, projectRoot, closedCallback)
        : new HopDesktopLiveSync(hopGui, projectRoot, closedCallback);
  }
}
