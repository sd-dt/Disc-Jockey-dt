package semmiedev.disc_jockey.gui.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;
import semmiedev.disc_jockey.Main;

public class PlaybackProgressOverlay implements HudElement {
    private static final int PROGRESS_BAR_WIDTH = 182;
    private static final int BACKGROUND_COLOR = 0x80808080;
    private static final int TEXT_COLOR = 0xEEFFFFFF;

    @Override
    public void extractRenderState(@Nullable GuiGraphicsExtractor context, @Nullable DeltaTracker tickCounter) {
        if (context == null) return;
        Minecraft client = Minecraft.getInstance();
        boolean isInGame = client.gui.screen() == null;

        if (Main.config.showProgressBarOverlay && Main.SONG_PLAYER.running && Main.SONG_PLAYER.song != null && isInGame) {
            int screenWidth = context.guiWidth();
            int screenHeight = context.guiHeight();

            int barX = screenWidth / 2 - PROGRESS_BAR_WIDTH / 2;
            int barY = screenHeight - 55;

            renderProgressBar(context, barX, barY, Main.SONG_PLAYER.getProgress(), Main.SONG_PLAYER.getFormattedTime(), 0x8000FF00);
        }

        if (Main.config.showProgressBarOverlay && Main.PREVIEWER.running && Main.PREVIEWER.getSong() != null && isInGame) {
            int screenWidth = context.guiWidth();
            int screenHeight = context.guiHeight();

            int barX = screenWidth / 2 - PROGRESS_BAR_WIDTH / 2;
            int barY = screenHeight - 42;

            renderProgressBar(context, barX, barY, Main.PREVIEWER.getProgress(), Main.PREVIEWER.getFormattedTime(), 0x80FF0000);
        }
    }

    private void renderProgressBar(GuiGraphicsExtractor context, int x, int y, float progress, String timeText, int progressColor) {
        context.fill(x, y, x + PROGRESS_BAR_WIDTH, y + 5, BACKGROUND_COLOR);

        int progressWidth = (int) (PROGRESS_BAR_WIDTH * progress);
        context.fill(x, y, x + progressWidth, y + 5, progressColor);

        Minecraft minecraft = Minecraft.getInstance();
        Font textRenderer = minecraft.font;
        int textX = x + (PROGRESS_BAR_WIDTH - textRenderer.width(timeText)) / 2;
        int textY = y - textRenderer.lineHeight;
        context.text(textRenderer, timeText, textX, textY, TEXT_COLOR, true);
    }
}