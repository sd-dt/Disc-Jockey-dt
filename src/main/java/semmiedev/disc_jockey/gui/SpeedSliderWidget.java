package semmiedev.disc_jockey.gui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.Main;
import semmiedev.disc_jockey.SongPlayer;

/**
 * 播放速率滑块：value∈[0,1] 用对数映射到 [SongPlayer.MIN_SPEED, SongPlayer.MAX_SPEED]，
 * 即 value=0 → 0.1x、value≈0.434 → 1.00x、value=1 → 20x。
 * 文案显示当前倍率；改动即时写回 SongPlayer.setSpeed()（该方法自带 0.1–20 夹取）。
 * 26.2：父类是 AbstractSliderButton（1.21.11 叫 SliderWidget），文案用 Component（1.21.11 是 Text）。
 */
public class SpeedSliderWidget extends AbstractSliderButton {
    private static final double SPEED_RANGE = SongPlayer.MAX_SPEED / SongPlayer.MIN_SPEED;

    public SpeedSliderWidget(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty(), speedToValue(Main.SONG_PLAYER.getSpeed()));
        updateMessage(); // AbstractSliderButton 的构造器不会调用 updateMessage，这里补一次初始文案
    }

    private static float valueToSpeed(double value) {
        return (float) (SongPlayer.MIN_SPEED * Math.pow(SPEED_RANGE, value));
    }

    private static double speedToValue(float speed) {
        if (!Float.isFinite(speed) || speed <= 0.0f) speed = 1.0f; // 兜底：字段可能被直接写过
        double clamped = Math.clamp(speed, SongPlayer.MIN_SPEED, SongPlayer.MAX_SPEED);
        return Math.clamp(Math.log(clamped / SongPlayer.MIN_SPEED) / Math.log(SPEED_RANGE), 0.0, 1.0);
    }

    @Override
    protected void updateMessage() {
        float speed = valueToSpeed(value);
        // Locale.ROOT：避免德语等区域把 1.00x 显示成 1,00x
        setMessage(Component.literal(String.format(java.util.Locale.ROOT, "%.2fx", speed)));
        Main.SONG_PLAYER.setSpeed(speed);
    }

    @Override
    protected void applyValue() {
        Main.SONG_PLAYER.setSpeed(valueToSpeed(value));
    }

    /** 命令等外部途径改了速率时同步滑块显示；拖动中两者本来就一致，不会互相干扰 */
    public void update() {
        float speed = Main.SONG_PLAYER.getSpeed();
        if (Math.abs(speed - valueToSpeed(value)) < 0.0001f) return;
        setValue(speedToValue(speed));
    }
}
