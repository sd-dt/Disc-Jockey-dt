package semmiedev.disc_jockey;

import semmiedev.disc_jockey.gui.SongListWidget;

import java.util.ArrayList;
import java.util.List;

public class Song {
    public final ArrayList<Note> uniqueNotes = new ArrayList<>();

    public long[] notes = new long[0];

    public short length, height, tempo, loopStartTick;
    public String fileName, filePath, name, author, originalAuthor, description, displayName;
    public byte autoSaving, autoSavingDuration, timeSignature, vanillaInstrumentCount, formatVersion, loop, maxLoopCount;
    public int minutesSpent, leftClicks, rightClicks, blocksAdded, blocksRemoved;
    public String importFileName;
    public SongLoader.SongFolder folder;
    /**
     * MIDI 懒解析标记：扫描歌曲列表时只为 .mid/.midi 建占位对象（name/notes/length/tempo/midiPpq/tempoChanges 还没解析），
     * 首次播放、试听或显示时长前由 SongLoader.ensureSongLoaded(Song) 读文件补齐并清掉该标记。NBS 歌曲恒为 false。
     */
    public boolean lazyMidi;

    // MIDI-specific fields for variable tempo
    public int midiPpq = 0; // pulses per quarter note, 0 for NBS songs
    public List<TempoChange> tempoChanges = null; // null for NBS songs

    public SongListWidget.SongEntry entry;
    public String searchableFileName, searchableName;

    public static class TempoChange {
        public final long midiTick;
        public final long mspqn; // microseconds per quarter note
        public TempoChange(long midiTick, long mspqn) {
            this.midiTick = midiTick;
            this.mspqn = mspqn;
        }
    }

    @Override
    public String toString() {
        return displayName;
    }

    public double millisecondsToTicks(long milliseconds) {
        // From NBS Format: The tempo of the song multiplied by 100 (for example, 1225 instead of 12.25). Measured in ticks per second.
        double songSpeed = (tempo / 100.0) / 20.0; // 20 Ticks per second (temp / 100 = 20) would be 1x speed
        double oneMsTo20TickFraction = 1.0 / 50.0;
        return milliseconds * oneMsTo20TickFraction * songSpeed;
    }

    public double ticksToMilliseconds(double ticks) {
        double songSpeed = (tempo / 100.0) / 20.0;
        double oneMsTo20TickFraction = 1.0 / 50.0;
        return ticks / oneMsTo20TickFraction / songSpeed;
    }

    public double getLengthInSeconds() {
        return ticksToMilliseconds(length) / 1000.0;
    }

}
