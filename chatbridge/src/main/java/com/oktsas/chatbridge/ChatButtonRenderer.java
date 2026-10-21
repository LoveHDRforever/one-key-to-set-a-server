package com.oktsas.chatbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws and hit-tests the button column.
 *
 * <p>Geometry: the buttons sit at the very bottom of the e33chat sidebar, stacked upwards, one per row,
 * anchored to the left edge of the sidebar and just above the input bar. That is the spot the owner pointed
 * at in the screenshot ("聊天面板侧边工具栏底部").</p>
 */
public final class ChatButtonRenderer {

    /** Side of one square button. */
    private static final int SIZE = 20;
    private static final int GAP = 4;
    private static final int MARGIN = 8;

    private ChatButtonRenderer() {
    }

    /** Top left corner of button {@code index}. */
    public static int[] slot(int sidebarX, int barTop, int index) {
        int x = sidebarX + MARGIN;
        int y = barTop - MARGIN - SIZE - index * (SIZE + GAP);
        return new int[]{x, y};
    }

    private static boolean inside(int[] at, double mouseX, double mouseY) {
        return mouseX >= at[0] && mouseX < at[0] + SIZE && mouseY >= at[1] && mouseY < at[1] + SIZE;
    }

    /**
     * Draws the column. Called from the tail of e33chat's {@code renderSidebar}, so it only happens while the
     * sidebar is actually on screen.
     */
    public static void render(GuiGraphics graphics, int sidebarX, int barTop, int mouseX, int mouseY) {
        List<ChatButtons.Button> buttons = ChatButtons.buttons();
        if (buttons.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < buttons.size(); i++) {
            ChatButtons.Button button = buttons.get(i);
            int[] at = slot(sidebarX, barTop, i);
            boolean hover = inside(at, mouseX, mouseY);
            boolean cooling = ChatButtons.coolingDown(button);

            // an "item frame" look: a dark plate with a lighter border, the vanilla item drawn inside
            graphics.fill(at[0] - 1, at[1] - 1, at[0] + SIZE + 1, at[1] + SIZE + 1,
                    hover ? 0xE04F7BC4 : 0xC01B1F26);
            graphics.fill(at[0], at[1], at[0] + SIZE, at[1] + SIZE, 0x60000000);
            graphics.renderItem(button.stack(), at[0] + 2, at[1] + 2);
            if (cooling) {
                graphics.fill(at[0], at[1], at[0] + SIZE, at[1] + SIZE, 0x80101010);
            }
            if (hover) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal("§f" + button.name));
                if (!button.commandLine().isEmpty()) {
                    lines.add(Component.literal("§7点击执行 §f/" + button.commandLine().replaceFirst("^/", "")));
                }
                if (cooling) {
                    lines.add(Component.literal("§8冷却中…"));
                }
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }

    /** True when the click landed on a button (and was handled). */
    public static boolean click(int sidebarX, int barTop, double mouseX, double mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }
        List<ChatButtons.Button> buttons = ChatButtons.buttons();
        for (int i = 0; i < buttons.size(); i++) {
            if (inside(slot(sidebarX, barTop, i), mouseX, mouseY)) {
                return ChatButtons.click(buttons.get(i));
            }
        }
        return false;
    }
}
