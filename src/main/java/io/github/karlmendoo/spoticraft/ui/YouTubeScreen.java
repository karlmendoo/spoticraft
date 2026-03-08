package io.github.karlmendoo.spoticraft.ui;

import io.github.karlmendoo.spoticraft.youtube.YouTubeService;
import io.github.karlmendoo.spoticraft.youtube.model.LibraryItem;
import io.github.karlmendoo.spoticraft.youtube.model.LibrarySnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.PlaybackSnapshot;
import io.github.karlmendoo.spoticraft.youtube.model.QueueEntry;
import io.github.karlmendoo.spoticraft.youtube.model.SearchSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public final class YouTubeScreen extends Screen {
    private static final int SCREEN_BACKGROUND_DARK_OVERLAY_COLOR = 0xCC0D1117;
    private static final int HEADER_TEXT_X = 32;
    private static final int HEADER_SUBTITLE_X = 44;
    private static final int HEADER_TITLE_Y = 10;
    private static final int HEADER_SUBTITLE_Y = 22;
    private static final int PLAYBACK_HINT_Y = 168;
    private static final int ITEM_TEXT_X_OFFSET = 38;
    private static final int ITEM_TEXT_TO_KIND_GAP = 10;

    private final YouTubeService service;
    private final AlbumArtCache albumArtCache;
    private final long openedAtMs = System.currentTimeMillis();
    private final List<ClickableRow> libraryRows = new ArrayList<>();
    private final List<ClickableRow> searchRows = new ArrayList<>();
    private final List<QueueAction> queueActions = new ArrayList<>();

    private TextFieldWidget searchField;
    private VolumeSlider volumeSlider;
    private ButtonWidget playPauseButton;
    private ButtonWidget shuffleButton;
    private ButtonWidget repeatButton;
    private LibraryTab selectedTab = LibraryTab.PLAYLISTS;
    private int libraryScroll;

    public YouTubeScreen(YouTubeService service, AlbumArtCache albumArtCache) {
        super(Text.literal("SpotiCraft"));
        this.service = service;
        this.albumArtCache = albumArtCache;
    }

    @Override
    protected void init() {
        int top = 24;
        int right = this.width - 24;

        this.searchField = this.addDrawableChild(new TextFieldWidget(this.textRenderer, right - 260, top + 10, 180, 20, Text.literal("Search YouTube")));
        this.searchField.setMaxLength(100);
        this.searchField.setPlaceholder(Text.literal("Videos, channels, playlists, collections"));
        this.searchField.setText("");

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Search"), button -> this.service.search(this.searchField.getText()))
            .dimensions(right - 72, top + 10, 56, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Connect"), button -> startAuthorization())
            .dimensions(24, top + 10, 78, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Open Config"), button -> Util.getOperatingSystem().open(this.service.config().path().toFile()))
            .dimensions(108, top + 10, 88, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), button -> this.service.refreshAll())
            .dimensions(202, top + 10, 68, 20)
            .build());

        int controlY = this.height - 106;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("◀◀"), button -> this.service.previousTrack())
            .dimensions(36, controlY, 44, 20)
            .build());
        this.playPauseButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Play"), button -> this.service.togglePlayPause())
            .dimensions(86, controlY, 60, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("▶▶"), button -> this.service.nextTrack())
            .dimensions(152, controlY, 44, 20)
            .build());
        this.shuffleButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Shuffle"), button -> this.service.toggleShuffle())
            .dimensions(36, controlY + 24, 78, 20)
            .build());
        this.repeatButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Repeat"), button -> this.service.cycleRepeat())
            .dimensions(120, controlY + 24, 76, 20)
            .build());
        this.volumeSlider = this.addDrawableChild(new VolumeSlider(36, controlY + 52, 160, 20));

        int tabX = this.width / 3 + 12;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Playlists"), button -> switchTab(LibraryTab.PLAYLISTS))
            .dimensions(tabX, 88, 72, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Liked"), button -> switchTab(LibraryTab.LIKED))
            .dimensions(tabX + 78, 88, 56, 20)
            .build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Recent"), button -> switchTab(LibraryTab.RECENT))
            .dimensions(tabX + 140, 88, 60, 20)
            .build());

        if (this.service.config().hasRefreshToken() || this.service.config().hasAccessToken()) {
            this.service.refreshAll();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, SCREEN_BACKGROUND_DARK_OVERLAY_COLOR);
        this.libraryRows.clear();
        this.searchRows.clear();
        this.queueActions.clear();

        PlaybackSnapshot playback = this.service.playback();
        SearchSnapshot search = this.service.search();
        LibrarySnapshot library = this.service.library();

        this.playPauseButton.setMessage(Text.literal(switch (playback.state()) {
            case PLAYING, BUFFERING -> "Pause";
            default -> "Play";
        }));
        this.shuffleButton.setMessage(Text.literal(playback.shuffleEnabled() ? "Shuffle: On" : "Shuffle"));
        this.repeatButton.setMessage(Text.literal("Repeat: " + playback.repeatMode().name()));
        this.volumeSlider.sync(playback.volumePercent());

        float eased = MathHelper.clamp((System.currentTimeMillis() - this.openedAtMs) / 220.0F, 0.0F, 1.0F);
        int slideY = (int) ((1.0F - eased) * 18.0F);

        int headerColor = 0xFFEEF2F7;
        int subColor = 0xFF99A3B1;
        int accent = 0xFFFF4343;

        int leftX = 24;
        int leftY = 56 + slideY;
        int leftWidth = this.width / 3 - 36;
        int centerX = this.width / 3 + 6;
        int centerY = leftY;
        int centerWidth = this.width / 3 - 18;
        int rightX = centerX + centerWidth + 12;
        int rightY = leftY;
        int rightWidth = this.width - rightX - 24;
        int panelHeight = this.height - leftY - 32;

        drawPanel(context, leftX, leftY, leftWidth, panelHeight, accent, 0.82F);
        drawPanel(context, centerX, centerY, centerWidth, panelHeight, 0xFF4DA3FF, 0.72F);
        drawPanel(context, rightX, rightY, rightWidth, panelHeight, 0xFFFF7EE3, 0.72F);

        context.drawText(this.textRenderer, Text.literal("SpotiCraft").formatted(Formatting.BOLD), HEADER_TEXT_X, HEADER_TITLE_Y, headerColor, false);
        context.drawText(this.textRenderer, Text.literal("The same polished Minecraft flow, now powered by YouTube."), HEADER_SUBTITLE_X, HEADER_SUBTITLE_Y, subColor, false);

        drawCurrentPlayback(context, playback, leftX, leftY, leftWidth, panelHeight);
        drawLibrary(context, library, centerX, centerY, centerWidth, panelHeight, mouseX, mouseY);
        drawSearch(context, search, rightX, rightY, rightWidth, panelHeight, mouseX, mouseY);
        drawStatus(context, leftX, panelHeight + leftY + 8, this.width - 48);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawCurrentPlayback(DrawContext context, PlaybackSnapshot playback, int x, int y, int width, int height) {
        context.drawText(this.textRenderer, Text.literal("Now Playing").formatted(Formatting.BOLD), x + 16, y + 16, 0xFFFFFFFF, false);
        context.drawText(
            this.textRenderer,
            Text.literal(playback.deviceName() + " · " + playback.state().label()),
            x + 16,
            y + 30,
            0xFFFFB7B7,
            false
        );

        Identifier art = this.albumArtCache.request(playback.imageUrl(), playback.trackId());
        if (art != null) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, art, x + 16, y + 52, 0, 0, 124, 124, 124, 124);
        } else {
            context.fill(x + 16, y + 52, x + 140, y + 176, 0x66533939);
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("▶"), x + 78, y + 107, 0xFFFFFFFF);
        }

        int textX = x + 150;
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(playback.title(), width - 170), textX, y + 60, 0xFFFFFFFF, false);
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(playback.artist(), width - 170), textX, y + 78, 0xFFD2D8E2, false);
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(playback.album(), width - 170), textX, y + 96, 0xFF9CA6B6, false);
        String queueSummary = playback.queueSize() > 0
            ? "Queue " + (playback.queueIndex() + 1) + "/" + playback.queueSize()
            : "Queue empty";
        context.drawText(this.textRenderer, Text.literal(queueSummary), textX, y + 114, 0xFFFFC0C0, false);

        int progressX = x + 16;
        int progressY = y + 192;
        int progressWidth = width - 32;
        int renderedProgress = this.service.renderedProgressMs();
        int filled = (int) ((renderedProgress / (float) Math.max(1, playback.durationMs())) * progressWidth);
        context.fill(progressX, progressY, progressX + progressWidth, progressY + 6, 0x44323A46);
        context.fill(progressX, progressY, progressX + filled, progressY + 6, 0xFFFF4343);
        context.drawText(this.textRenderer, Text.literal(formatMillis(renderedProgress)), progressX, progressY + 10, 0xFFEFF3F7, false);
        context.drawText(this.textRenderer, Text.literal(formatMillis(playback.durationMs())), progressX + progressWidth - 30, progressY + 10, 0xFFEFF3F7, false);
        drawQueue(context, x + 16, progressY + 28, width - 32, Math.max(0, this.height - PLAYBACK_HINT_Y - (progressY + 54)));

        int hintY = this.height - PLAYBACK_HINT_Y;
        context.drawText(this.textRenderer, Text.literal("Quick keys"), x + 16, hintY, 0xFFFFC0C0, false);
        context.drawText(this.textRenderer, Text.literal("O open · J previous · K play/pause · L next"), x + 16, hintY + 14, 0xFFDAE1EA, false);
        context.drawText(this.textRenderer, Text.literal("Click to play · Shift-click a result to add it to the queue."), x + 16, hintY + 30, 0xFFBBC4CF, false);
    }

    private void drawQueue(DrawContext context, int x, int y, int width, int availableHeight) {
        List<QueueEntry> queueEntries = this.service.queueEntries();
        context.drawText(this.textRenderer, Text.literal("Queue").formatted(Formatting.BOLD), x, y, 0xFFFFFFFF, false);
        if (queueEntries.isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("Queue up a track to keep music going."), x, y + 16, 0xFFBBC4CF, false);
            return;
        }
        int maxRows = Math.max(1, Math.min(5, availableHeight / 28));
        int startIndex = 0;
        for (int index = 0; index < queueEntries.size(); index++) {
            if (queueEntries.get(index).current()) {
                startIndex = Math.max(0, Math.min(index, queueEntries.size() - maxRows));
                break;
            }
        }
        for (int rowIndex = 0; rowIndex < maxRows && startIndex + rowIndex < queueEntries.size(); rowIndex++) {
            QueueEntry entry = queueEntries.get(startIndex + rowIndex);
            int rowY = y + 18 + rowIndex * 28;
            boolean current = entry.current();
            context.fill(x, rowY, x + width, rowY + 24, current ? 0x66564242 : 0x33212A34);
            Identifier art = this.albumArtCache.request(entry.imageUrl(), entry.trackId());
            if (art != null) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, art, x + 4, rowY + 4, 0, 0, 16, 16, 16, 16);
            } else {
                context.fill(x + 4, rowY + 4, x + 20, rowY + 20, 0x55374455);
            }
            context.drawText(this.textRenderer, Text.literal((entry.index() + 1) + "."), x + 24, rowY + 8, 0xFFFFC0C0, false);
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(entry.title(), Math.max(1, width - 110)), x + 40, rowY + 4, 0xFFFFFFFF, false);
            context.drawText(this.textRenderer, this.textRenderer.trimToWidth(entry.artist(), Math.max(1, width - 110)), x + 40, rowY + 14, 0xFFB7BEC9, false);
            int actionX = x + width - 34;
            drawQueueAction(context, actionX, rowY + 4, "▲", entry.index(), QueueActionType.UP);
            drawQueueAction(context, actionX + 10, rowY + 4, "▼", entry.index(), QueueActionType.DOWN);
            drawQueueAction(context, actionX + 20, rowY + 4, "✕", entry.index(), QueueActionType.REMOVE);
            this.queueActions.add(new QueueAction(x, rowY, width - 36, 24, entry.index(), QueueActionType.PLAY));
        }
    }

    private void drawQueueAction(DrawContext context, int x, int y, String label, int queueIndex, QueueActionType actionType) {
        context.drawText(this.textRenderer, Text.literal(label), x, y, 0xFFFFC0C0, false);
        this.queueActions.add(new QueueAction(x - 1, y - 1, 10, 10, queueIndex, actionType));
    }

    private void drawLibrary(DrawContext context, LibrarySnapshot library, int x, int y, int width, int height, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, Text.literal("Library").formatted(Formatting.BOLD), x + 16, y + 16, 0xFFFFFFFF, false);

        List<LibraryItem> items = switch (this.selectedTab) {
            case PLAYLISTS -> library.playlists();
            case LIKED -> library.likedTracks();
            case RECENT -> library.recentlyPlayed();
        };
        int visibleCount = Math.max(1, (height - 100) / 42);
        this.libraryScroll = MathHelper.clamp(this.libraryScroll, 0, Math.max(0, items.size() - visibleCount));
        for (int index = 0; index < visibleCount && index + this.libraryScroll < items.size(); index++) {
            LibraryItem item = items.get(index + this.libraryScroll);
            int rowY = y + 56 + index * 42;
            drawItemRow(context, x + 12, rowY, width - 24, item, mouseX, mouseY, this.libraryRows);
        }
        if (items.isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("No items yet. Connect YouTube and press Refresh."), x + 16, y + 68, 0xFFDFE5EC, false);
        }
    }

    private void drawSearch(DrawContext context, SearchSnapshot search, int x, int y, int width, int height, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, Text.literal("Search").formatted(Formatting.BOLD), x + 16, y + 16, 0xFFFFFFFF, false);
        int currentY = y + 42;
        currentY = drawSearchSection(context, x, width, mouseX, mouseY, currentY, "Videos", search.tracks());
        currentY = drawSearchSection(context, x, width, mouseX, mouseY, currentY + 6, "Collections", search.albums());
        currentY = drawSearchSection(context, x, width, mouseX, mouseY, currentY + 6, "Channels", search.artists());
        drawSearchSection(context, x, width, mouseX, mouseY, currentY + 6, "Playlists", search.playlists());
    }

    private int drawSearchSection(DrawContext context, int x, int width, int mouseX, int mouseY, int startY, String title, List<LibraryItem> items) {
        context.drawText(this.textRenderer, Text.literal(title), x + 16, startY, 0xFFF4F7FB, false);
        int rowY = startY + 16;
        for (int i = 0; i < Math.min(items.size(), 3); i++) {
            drawItemRow(context, x + 12, rowY, width - 24, items.get(i), mouseX, mouseY, this.searchRows);
            rowY += 38;
        }
        if (items.isEmpty()) {
            context.drawText(this.textRenderer, Text.literal("No results"), x + 16, rowY + 4, 0xFFCCD3DD, false);
            rowY += 20;
        }
        return rowY;
    }

    private void drawItemRow(DrawContext context, int x, int y, int width, LibraryItem item, int mouseX, int mouseY, List<ClickableRow> registry) {
        boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + 32;
        int background = hovered ? 0x667B8DA3 : 0x44212A34;
        Text kindText = Text.literal(item.kind().name());
        int kindWidth = this.textRenderer.getWidth(kindText);
        int kindX = x + width - 8 - kindWidth;
        int textWidth = Math.max(1, kindX - (x + ITEM_TEXT_X_OFFSET) - ITEM_TEXT_TO_KIND_GAP);
        context.fill(x, y, x + width, y + 32, background);
        if (hovered) {
            context.fill(x, y, x + 3, y + 32, 0xFFFF4343);
        }

        Identifier art = this.albumArtCache.request(item.imageUrl(), item.kind() == LibraryItem.Kind.TRACK ? item.id() : "");
        if (art != null) {
            context.drawTexture(RenderPipelines.GUI_TEXTURED, art, x + 6, y + 4, 0, 0, 24, 24, 24, 24);
        } else {
            context.fill(x + 6, y + 4, x + 30, y + 28, 0x55374455);
        }
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(item.title(), textWidth), x + ITEM_TEXT_X_OFFSET, y + 6, 0xFFFFFFFF, false);
        context.drawText(this.textRenderer, this.textRenderer.trimToWidth(item.subtitle(), textWidth), x + ITEM_TEXT_X_OFFSET, y + 18, 0xFFC6CED8, false);
        context.drawText(this.textRenderer, kindText, kindX, y + 10, item.playable() ? 0xFFFFB7B7 : 0xFFFFD479, false);
        registry.add(new ClickableRow(x, y, width, 32, item));
    }

    private void drawStatus(DrawContext context, int x, int y, int width) {
        String error = this.service.errorMessage();
        String status = this.service.busy() ? "Working with YouTube…" : this.service.statusMessage();
        if (!error.isBlank()) {
            context.fill(x, y, x + width, y + 18, 0x99A32323);
            context.drawText(this.textRenderer, Text.literal(error), x + 8, y + 5, 0xFFFFFFFF, false);
        } else {
            context.fill(x, y, x + width, y + 18, 0x66212A34);
            context.drawText(this.textRenderer, Text.literal(status), x + 8, y + 5, 0xFFE5EAF1, false);
        }
    }

    private void drawPanel(DrawContext context, int x, int y, int width, int height, int accent, float alphaMultiplier) {
        int alpha = (int) (alphaMultiplier * 255.0F);
        int background = alpha << 24 | 0x0F1216;
        context.fill(x, y, x + width, y + height, background);
        context.fill(x, y, x + width, y + 2, accent);
        context.fill(x, y + height - 1, x + width, y + height, 0x33242B32);
        context.fill(x, y, x + 1, y + height, 0x33242B32);
        context.fill(x + width - 1, y, x + width, y + height, 0x33242B32);
    }

    private void switchTab(LibraryTab tab) {
        this.selectedTab = tab;
        this.libraryScroll = 0;
    }

    private void startAuthorization() {
        try {
            URI uri = this.service.startAuthorizationFlow();
            MinecraftClient.getInstance().keyboard.setClipboard(uri.toString());
            if (this.service.config().openBrowserOnAuth) {
                Util.getOperatingSystem().open(uri);
            }
        } catch (IllegalStateException exception) {
            this.service.setErrorMessage(exception.getMessage());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int centerX = this.width / 3 + 6;
        int centerWidth = this.width / 3 - 18;
        if (mouseX >= centerX && mouseX <= centerX + centerWidth) {
            this.libraryScroll = Math.max(0, this.libraryScroll - (int) Math.signum(verticalAmount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            for (QueueAction action : this.queueActions) {
                if (!action.contains(click.x(), click.y())) {
                    continue;
                }
                switch (action.actionType()) {
                    case PLAY -> this.service.playQueueIndex(action.queueIndex());
                    case UP -> this.service.moveQueueItem(action.queueIndex(), Math.max(0, action.queueIndex() - 1));
                    case DOWN -> this.service.moveQueueItem(action.queueIndex(), Math.min(this.service.queueEntries().size() - 1, action.queueIndex() + 1));
                    case REMOVE -> this.service.removeQueueItem(action.queueIndex());
                }
                return true;
            }
            for (ClickableRow row : this.libraryRows) {
                if (row.contains(click.x(), click.y())) {
                    handleItemClick(row.item());
                    return true;
                }
            }
            for (ClickableRow row : this.searchRows) {
                if (row.contains(click.x(), click.y()) && row.item().playable()) {
                    handleItemClick(row.item());
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static String formatMillis(int millis) {
        int totalSeconds = Math.max(0, millis / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }

    private static boolean isQueueModifierDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
            || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private void handleItemClick(LibraryItem item) {
        if (isQueueModifierDown() && item.playable()) {
            this.service.queue(item);
            return;
        }
        this.service.play(item);
    }

    private enum LibraryTab {
        PLAYLISTS("Playlists"),
        LIKED("Liked Videos"),
        RECENT("Recently Played");

        private final String label;

        LibraryTab(String label) {
            this.label = label;
        }
    }

    private record ClickableRow(int x, int y, int width, int height, LibraryItem item) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }

    private record QueueAction(int x, int y, int width, int height, int queueIndex, QueueActionType actionType) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height;
        }
    }

    private enum QueueActionType {
        PLAY,
        UP,
        DOWN,
        REMOVE
    }

    private final class VolumeSlider extends SliderWidget {
        private boolean syncing;
        private int lastCommittedVolume = -1;

        private VolumeSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Text.literal("Volume"), 0.7D);
            updateMessage();
        }

        private void sync(int volumePercent) {
            if (this.sliderFocused) {
                return;
            }
            int clamped = MathHelper.clamp(volumePercent, 0, 100);
            if ((int) Math.round(this.value * 100.0D) == clamped) {
                return;
            }
            this.syncing = true;
            this.value = clamped / 100.0D;
            updateMessage();
            this.syncing = false;
            this.lastCommittedVolume = clamped;
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Volume " + (int) Math.round(this.value * 100.0D) + '%'));
        }

        @Override
        protected void applyValue() {
            if (this.syncing) {
                return;
            }
            int volume = MathHelper.clamp((int) Math.round(this.value * 100.0D), 0, 100);
            if (volume != this.lastCommittedVolume) {
                this.lastCommittedVolume = volume;
                YouTubeScreen.this.service.setVolume(volume);
            }
        }
    }
}
