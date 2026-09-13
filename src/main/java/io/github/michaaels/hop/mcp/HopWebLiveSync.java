package io.github.michaaels.hop.mcp;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.ServerPushSessionFacade;

/**
 * Hop Web adapter for one RAP UI session.
 *
 * <p>Server push keeps background-originated updates connected to this browser session. Hop's
 * facade selects the RAP implementation at runtime, so this plugin does not depend directly on RAP
 * internals and remains installable in Hop Desktop.
 */
final class HopWebLiveSync extends HopLiveUiSync {
  HopWebLiveSync(HopGui hopGui, Path projectRoot, Runnable closedCallback) throws IOException {
    super(hopGui, projectRoot, "web", closedCallback);
  }

  @Override
  protected void startUiDispatch() {
    ServerPushSessionFacade.start();
  }

  @Override
  protected void stopUiDispatch() {
    ServerPushSessionFacade.stop();
  }
}
