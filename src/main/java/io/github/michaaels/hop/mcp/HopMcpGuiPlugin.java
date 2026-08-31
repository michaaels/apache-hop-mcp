package io.github.michaaels.hop.mcp;

import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.menu.GuiMenuElement;
import org.apache.hop.ui.hopgui.HopGui;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.MessageBox;

@GuiPlugin
public class HopMcpGuiPlugin {
  public static final String MENU_ID="40250-menu-tools-apache-hop-mcp";
  @GuiMenuElement(root=HopGui.ID_MAIN_MENU,id=MENU_ID,label="Apache Hop MCP…",toolTip="Show Apache Hop MCP installation and Codex configuration",parentId=HopGui.ID_MAIN_MENU_TOOLS_PARENT_ID,separator=true)
  public void showMcpInfo() { HopGui gui=HopGui.getInstance(); MessageBox box=new MessageBox(gui.getShell(),SWT.OK|SWT.ICON_INFORMATION); box.setText("Apache Hop MCP 0.3.0"); box.setMessage("Apache Hop MCP is installed.\n\nHeadless MCP command:\n  hop mcp --root <project>\n\nThe server uses MCP over STDIO and is read-only by default.\nUse --allow-deep-check only when external metadata/database access is acceptable."); box.open(); }
}
