package semmiedev.disc_jockey.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;

public class PreviewTimeSliderWidget extends AbstractSliderButton {

    public PreviewTimeSliderWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), 0);
    }

    private static String padZeroes(int number) {
        StringBuilder builder = new StringBuilder("" + number);
        while (builder.length() < 2) {
            builder.insert(0, '0');
        } return builder.toString();
    }

    private static String formatTimestamp(int seconds) {
        return padZeroes(seconds / 60) + ":" + padZeroes(seconds % 60);
    }

    @Override
    protected void updateMessage() {
        if (Main.PREVIEWER.getSong() == null) {
            setMessage(Component.empty());
        } else {
            setMessage(Component.literal(formatTimestamp((int) Main.PREVIEWER.getSongElapsedSeconds()) + " / " + formatTimestamp((int) Main.PREVIEWER.getSong().getLengthInSeconds())));
        }
    }

    @Override
    protected void applyValue() {
        if(Main.PREVIEWER.getSong() == null) return;
        double total = Main.PREVIEWER.getSong().getLengthInSeconds();
        double seconds = value * total;
        Main.PREVIEWER.setSongElapsedSeconds(seconds);
    }

    public void update() {
        if (Main.PREVIEWER.getSong() == null) {
            value = 0;
            setMessage(Component.empty());
            return;
        }
        double elapsed = Main.PREVIEWER.getSongElapsedSeconds();
        double total = Main.PREVIEWER.getSong().getLengthInSeconds();
        value = elapsed / total;
        updateMessage();
    }
}