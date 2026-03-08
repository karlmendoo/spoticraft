package io.github.karlmendoo.spoticraft.ui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Custom styled button for SpotiCraft UI.
 * Features a dark background with Spotify green accent on hover.
 */
public class SpotiCraftButton extends ButtonWidget {
    private static final int NORMAL_BG = 0xFF2A2A2A;
    private static final int HOVER_BG = 0xFF1DB954;
    private static final int DISABLED_BG = 0xFF1A1A1A;
    private static final int BORDER_COLOR = 0xFF3A3A3A;
    private static final int HOVER_BORDER = 0xFF1DB954;

    public SpotiCraftButton(int x, int y, int width, int height, Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int bg;
        int border;
        if (!this.active) {
            bg = DISABLED_BG;
            border = BORDER_COLOR;
        } else if (this.isHovered()) {
            bg = HOVER_BG;
            border = HOVER_BORDER;
        } else {
            bg = NORMAL_BG;
            border = BORDER_COLOR;
        }

        // Background
        context.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bg);

        // Border
        context.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + 1, border);
        context.fill(this.getX(), this.getY() + this.height - 1, this.getX() + this.width, this.getY() + this.height, border);
        context.fill(this.getX(), this.getY(), this.getX() + 1, this.getY() + this.height, border);
        context.fill(this.getX() + this.width - 1, this.getY(), this.getX() + this.width, this.getY() + this.height, border);

        // Text
        int textColor = this.active ? 0xFFFFFFFF : 0xFF666666;
        context.drawCenteredTextWithShadow(
                net.minecraft.client.MinecraftClient.getInstance().textRenderer,
                this.getMessage(), this.getX() + this.width / 2,
                this.getY() + (this.height - 8) / 2, textColor);
    }
}
