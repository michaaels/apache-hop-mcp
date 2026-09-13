package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.hop.ui.hopgui.HopGui;

/** Desktop adapter for one SWT Hop GUI process. */
final class HopDesktopLiveSync extends HopLiveUiSync {
  HopDesktopLiveSync(HopGui hopGui, Path projectRoot, Runnable closedCallback) throws IOException {
    super(hopGui, projectRoot, "desktop", closedCallback);
  }
}
