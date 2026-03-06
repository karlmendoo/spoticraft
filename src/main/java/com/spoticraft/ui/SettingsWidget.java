package com.spoticraft.ui;

import com.spoticraft.api.SpotifyAuth;
import com.spoticraft.config.SpotiCraftConfig;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Settings tab — configure Spotify Client ID and manage authentication.
 */
public class SettingsWidget {

    private final int x, y, w, h;
    private final SpotiCraftScreen parent;

    private String clientIdInput = "";
    private boolean clientIdFieldFocused = false;
    private String statusMessage = "";
    private int statusTick = 0;

    private static final int PADDING = 16;
    private static final int FIELD_HEIGHT = 22;

    public SettingsWidget(int x, int y, int w, int h, SpotiCraftScreen parent) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.parent = parent;
        // Pre-fill with stored client ID
        this.clientIdInput = SpotiCraftConfig.getInstance().clientId;
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta, TextRenderer tr) {
        context.fill(x, y, x + w, y + h, SpotiCraftConfig.COLOR_BG);

        // Title
        context.drawText(tr, "Settings", x + PADDING, y + PADDING, SpotiCraftConfig.COLOR_TEXT, false);
        context.fill(x + PADDING, y + PADDING + 12, x + w - PADDING, y + PADDING + 13, 0xFF333333);

        int curY = y + PADDING + 24;

        // ── Client ID field ──────────────────────────────────────────────────
        context.drawText(tr, "Spotify Client ID:", x + PADDING, curY, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);
        curY += 14;

        int fieldW = w - PADDING * 2;
        int fieldX = x + PADDING;
        context.fill(fieldX, curY, fieldX + fieldW, curY + FIELD_HEIGHT, 0xFF1A1A1A);
        int borderColor = clientIdFieldFocused ? SpotiCraftConfig.COLOR_ACCENT : 0xFF333333;
        drawBorder(context, fieldX, curY, fieldW, FIELD_HEIGHT, borderColor);

        String displayId = clientIdInput.isEmpty() && !clientIdFieldFocused
                ? "Enter your Spotify Client ID..." : clientIdInput;
        int displayColor = clientIdInput.isEmpty() && !clientIdFieldFocused ? 0xFF555555 : SpotiCraftConfig.COLOR_TEXT;
        if (clientIdFieldFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            displayId += "│";
        }
        context.drawText(tr, displayId, fieldX + 6, curY + (FIELD_HEIGHT - 8) / 2, displayColor, false);

        curY += FIELD_HEIGHT + 10;

        // Save button
        boolean saveHovered = mouseX >= fieldX && mouseX < fieldX + 60 && mouseY >= curY && mouseY < curY + 18;
        context.fill(fieldX, curY, fieldX + 60, curY + 18,
                saveHovered ? SpotiCraftConfig.COLOR_ACCENT : 0xFF1DB954AA);
        context.drawCenteredTextWithShadow(tr, "Save", fieldX + 30, curY + 5,
                saveHovered ? 0xFF000000 : SpotiCraftConfig.COLOR_TEXT);

        curY += 30;

        // ── Authentication section ────────────────────────────────────────────
        context.drawText(tr, "Authentication", x + PADDING, curY, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);
        context.fill(x + PADDING, curY + 12, x + w - PADDING, curY + 13, 0xFF333333);
        curY += 22;

        boolean authenticated = SpotifyAuth.getInstance().isAuthenticated();
        SpotifyAuth.AuthState authState = SpotifyAuth.getInstance().getAuthState();

        // Status display
        String authStatus;
        int authColor;
        if (authenticated) {
            authStatus = "✓ Connected to Spotify";
            authColor = SpotiCraftConfig.COLOR_ACCENT;
        } else if (authState == SpotifyAuth.AuthState.CONNECTING || authState == SpotifyAuth.AuthState.WAITING_CALLBACK) {
            authStatus = "⏳ " + (authState == SpotifyAuth.AuthState.WAITING_CALLBACK
                    ? "Waiting for authorization in browser..." : "Connecting...");
            authColor = 0xFFFFAA00;
        } else if (authState == SpotifyAuth.AuthState.FAILED) {
            authStatus = "✗ Authentication failed";
            authColor = 0xFFFF5555;
        } else {
            authStatus = "○ Not connected";
            authColor = SpotiCraftConfig.COLOR_TEXT_SECONDARY;
        }

        context.drawText(tr, authStatus, x + PADDING, curY, authColor, false);
        curY += 18;

        // Connect / Disconnect button
        String btnLabel = authenticated ? "Disconnect" : "Connect to Spotify";
        int btnW = tr.getWidth(btnLabel) + 20;
        boolean btnHovered = mouseX >= x + PADDING && mouseX < x + PADDING + btnW &&
                             mouseY >= curY && mouseY < curY + 20;

        int btnBg = authenticated ? 0xFF552222 : 0xFF1DB954;
        if (btnHovered) btnBg = authenticated ? 0xFF883333 : 0xFF22DD66;

        context.fill(x + PADDING, curY, x + PADDING + btnW, curY + 20, btnBg);
        context.drawCenteredTextWithShadow(tr, btnLabel, x + PADDING + btnW / 2, curY + 6,
                SpotiCraftConfig.COLOR_TEXT);

        curY += 30;

        // ── HUD settings ─────────────────────────────────────────────────────
        context.drawText(tr, "HUD Overlay", x + PADDING, curY, SpotiCraftConfig.COLOR_TEXT_SECONDARY, false);
        context.fill(x + PADDING, curY + 12, x + w - PADDING, curY + 13, 0xFF333333);
        curY += 22;

        boolean hudVisible = SpotiCraftConfig.getInstance().hudVisible;
        boolean hudHovered = mouseX >= x + PADDING && mouseX < x + PADDING + 100 &&
                             mouseY >= curY && mouseY < curY + 18;
        context.fill(x + PADDING, curY, x + PADDING + 100, curY + 18, hudHovered ? 0xFF333333 : 0xFF1A1A1A);
        drawBorder(context, x + PADDING, curY, 100, 18, 0xFF333333);
        context.drawCenteredTextWithShadow(tr, "HUD: " + (hudVisible ? "ON" : "OFF"),
                x + PADDING + 50, curY + 5,
                hudVisible ? SpotiCraftConfig.COLOR_ACCENT : SpotiCraftConfig.COLOR_TEXT_SECONDARY);

        // ── Status message ────────────────────────────────────────────────────
        if (statusTick > 0 && !statusMessage.isEmpty()) {
            int alpha = Math.min(255, statusTick * 4);
            context.drawText(tr, statusMessage, x + PADDING, y + h - 24,
                    (alpha << 24) | 0xFFFFFF, false);
        }

        // Redirect URI info
        context.drawText(tr, "Redirect URI: http://localhost:4381/callback",
                x + PADDING, y + h - 12, 0xFF444444, false);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        int fieldW = w - PADDING * 2;
        int fieldX = x + PADDING;
        int curY = y + PADDING + 38;

        // Client ID field focus
        if (mouseX >= fieldX && mouseX <= fieldX + fieldW &&
                mouseY >= curY && mouseY <= curY + FIELD_HEIGHT) {
            clientIdFieldFocused = true;
            return true;
        }
        clientIdFieldFocused = false;

        curY += FIELD_HEIGHT + 10;

        // Save button
        if (mouseX >= fieldX && mouseX < fieldX + 60 && mouseY >= curY && mouseY < curY + 18) {
            saveClientId();
            return true;
        }

        curY += 30 + 22;

        boolean authenticated = SpotifyAuth.getInstance().isAuthenticated();
        String btnLabel = authenticated ? "Disconnect" : "Connect to Spotify";
        int btnW = 9 * btnLabel.length() + 20; // approximate

        // Connect / Disconnect button
        if (mouseX >= x + PADDING && mouseX < x + PADDING + btnW + 60 &&
                mouseY >= curY && mouseY < curY + 20) {
            if (authenticated) {
                SpotifyAuth.getInstance().disconnect();
                showStatus("Disconnected from Spotify.");
            } else {
                connectToSpotify();
            }
            return true;
        }

        curY += 30 + 22;

        // HUD toggle
        if (mouseX >= x + PADDING && mouseX < x + PADDING + 100 &&
                mouseY >= curY && mouseY < curY + 18) {
            SpotiCraftConfig.getInstance().hudVisible = !SpotiCraftConfig.getInstance().hudVisible;
            SpotiCraftConfig.save();
            return true;
        }

        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (clientIdFieldFocused && keyCode == 259) { // Backspace
            if (!clientIdInput.isEmpty()) {
                clientIdInput = clientIdInput.substring(0, clientIdInput.length() - 1);
            }
            return true;
        }
        return false;
    }

    public boolean charTyped(char chr, int modifiers) {
        if (clientIdFieldFocused && chr >= 32 && chr != 127) {
            clientIdInput += chr;
            return true;
        }
        return false;
    }

    private void saveClientId() {
        SpotiCraftConfig config = SpotiCraftConfig.getInstance();
        config.clientId = clientIdInput.trim();
        SpotiCraftConfig.save();
        showStatus("Client ID saved.");
    }

    private void connectToSpotify() {
        String clientId = SpotiCraftConfig.getInstance().clientId;
        if (clientId == null || clientId.isBlank()) {
            showStatus("Please enter a Spotify Client ID first.");
            return;
        }
        showStatus("Opening browser for Spotify authorization...");
        SpotifyAuth.getInstance().startAuthFlow(clientId);
    }

    private void showStatus(String msg) {
        this.statusMessage = msg;
        this.statusTick = 160;
    }

    public void tick() {
        if (statusTick > 0) statusTick--;
    }

    private void drawBorder(DrawContext ctx, int bx, int by, int bw, int bh, int color) {
        ctx.fill(bx, by, bx + bw, by + 1, color);
        ctx.fill(bx, by + bh - 1, bx + bw, by + bh, color);
        ctx.fill(bx, by, bx + 1, by + bh, color);
        ctx.fill(bx + bw - 1, by, bx + bw, by + bh, color);
    }
}
