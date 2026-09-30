package semmiedev.disc_jockey;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

import java.util.ArrayList;

@me.shedaniel.autoconfig.annotation.Config(name = Main.MOD_ID)
@me.shedaniel.autoconfig.annotation.Config.Gui.Background("textures/block/note_block.png")
public class Config implements ConfigData {
    public boolean hideWarning;
    @ConfigEntry.Gui.Tooltip(count = 2) public boolean disableAsyncPlayback;
    @ConfigEntry.Gui.Tooltip(count = 2) public boolean omnidirectionalNoteBlockSounds = true;

    public enum ExpectedServerVersion {
        All,
        v1_20_4_Or_Earlier,
        v1_20_5_Or_Later;

        @Override
        public String toString() {
            return switch (this) {
                case All -> "All (universal)";
                case v1_20_4_Or_Earlier -> "≤1.20.4";
                case v1_20_5_Or_Later -> "≥1.20.5";
            };
        }
    }

    // 预期服务器版本不再由用户配置：它现在是「自动检测结果的存放处」——
    // Main#updateExpectedServerVersion 每 tick 按玩家的方块交互距离刷新该字段（只在值变化时写），
    // Tuner / Util 只读取它来判定音符盒可达范围，因此从配置界面隐藏（26.2 基线里是可见的按钮选项）。
    @ConfigEntry.Gui.Excluded
    public ExpectedServerVersion expectedServerVersion = ExpectedServerVersion.All;

    public enum TuningSpeed {
        Snail,
        Safe,
        Spigot,
        Flash;

        @Override
        public String toString() {
            return switch(this) {
                case Snail -> "Snail (10/sec)";
                case Safe -> "Safe (20/sec)";
                case Spigot -> "Spigot (recommended)";
                case Flash -> "Flash";
            };
        }
    }

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip(count = 7)
    public TuningSpeed tuningSpeed = TuningSpeed.Spigot;

    public enum PlaybackPacketRatelimit {
        Limit100,
        Limit200,
        Limit300,
        Limit500,
        NoLimit;

        @Override
        public String toString() {
            return switch(this) {
                case Limit100 -> "100 Packets/sec";
                case Limit200 -> "200 Packets/sec";
                case Limit300 -> "300 Packets/sec";
                case Limit500 -> "500 Packets/sec";
                case NoLimit -> "No Limit";
            };
        }

        public int getReducePacketsPer100Millis() {
            return switch(this) {
                case Limit100 -> 80 / 10;
                case Limit200 -> 160 / 10;
                case Limit300 -> 240 / 10;
                case Limit500 -> 400 / 10;
                case NoLimit -> Integer.MAX_VALUE;
            };
        }

        public int getMaxPacketsPer100Millis() {
            return switch(this) {
                case Limit100 -> 90 / 10;
                case Limit200 -> 190 / 10;
                case Limit300 -> 290 / 10;
                case Limit500 -> 490 / 10;
                case NoLimit -> Integer.MAX_VALUE;
            };
        }

    }

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip(count = 4)
    public PlaybackPacketRatelimit playbackPacketRatelimit = PlaybackPacketRatelimit.Limit500;


    @ConfigEntry.Gui.Tooltip()
    public float delayPlaybackStartBySecs;

    @ConfigEntry.Gui.Tooltip(count = 3) public boolean instrumentDetectionWorkaround = true;

    // PORT(26.2): 实验性 MIDI 开关已删除 —— 现在 .mid / .midi 与 .nbs 一样是无条件支持的
    // （SongLoader 里不再有该开关的判定，MidiLoader 自动完成转换）。

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean autoScrollToLastSelected = true;

    @ConfigEntry.Gui.Tooltip()
    public boolean rememberLastSelectedOnRestart;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean showProgressBarOverlay = true;

    @ConfigEntry.Gui.Tooltip(count = 2)
    public boolean autoSwitchNoteBlocks = false;

    @ConfigEntry.Gui.Excluded
    public ArrayList<String> favorites = new ArrayList<>();

    @ConfigEntry.Gui.Excluded
    public String lastSelectedSong = "";

}
