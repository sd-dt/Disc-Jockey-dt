package semmiedev.disc_jockey;

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import javax.sound.midi.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.LongFunction;

/**
 * Experimental MIDI file loader for Disc Jockey.
 * Converts MIDI files to the Song format used by the mod.
 * Supports General MIDI instrument mapping and variable tempo.
 */
public class MidiLoader {

    // MIDI → NBS 音色映射全部来自 ONBS（Open Note Block Studio）的 midi_ins / midi_drum 表，
    // 见 OnbsMidiInstruments（含每个音色的八度补偿与打击乐专用音高）。
    // 与 NBS 导入同一个 MIDI 的结果保持一致；旧的"大致对应"映射表已删除。

    public static final int PERCUSSION_CHANNEL = 9; // 0-indexed MIDI channel 10

    private record NoteData(long midiTick, int channel, int songPitch, int velocity, int program, NoteBlockInstrument instrument) {}

    /**
     * 按 ONBS 的表把一个 MIDI 音符解析成「歌曲音高 + 乐器」：
     * - 普通通道：乐器取 midi_ins[program,1]，音高加上 midi_ins[program,2] 的八度补偿；
     * - 打击乐通道（10）：乐器取 midi_drum[note,1]，音高取 midi_drum[note,2] + 33（0-87 音阶）再换算回 MIDI 音高。
     */
    private static NoteData makeNoteData(long midiTick, int channel, int midiPitch, int velocity, int program) {
        if (channel == PERCUSSION_CHANNEL) {
            int songPitch = OnbsMidiInstruments.drumSongPitch(midiPitch); // 0-87 音阶，0 = MIDI 21
            return new NoteData(midiTick, channel, songPitch + 21, velocity, program,
                    OnbsMidiInstruments.drumInstrument(midiPitch));
        }
        int songPitch = midiPitch + 12 * OnbsMidiInstruments.octaveForProgram(program);
        return new NoteData(midiTick, channel, songPitch, velocity, program,
                OnbsMidiInstruments.instrumentForProgram(program));
    }

    /**
     * Load a MIDI file and convert it to a Song object.
     */
    public static Song loadFromMidi(File midiFile) throws IOException {
        Sequence sequence;
        try {
            sequence = MidiSystem.getSequence(midiFile);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Invalid MIDI data", e);
        }

        if (sequence == null) {
            throw new IOException("Failed to read MIDI file (null sequence)");
        }

        float divisionType = sequence.getDivisionType();
        int resolution = sequence.getResolution();
        int ppq = (divisionType == Sequence.PPQ) ? resolution : resolution * 4;

        // Track tempo changes
        List<Song.TempoChange> tempoChanges = new ArrayList<>();
        List<NoteData> rawNotes = new ArrayList<>();
        int[] channelInstruments = new int[16];
        Arrays.fill(channelInstruments, -1);
        long maxMidiTick = 0;
        short tempo = 120; // default 120 BPM

        // First pass: collect tempo changes and note data
        for (Track track : sequence.getTracks()) {
            if (track == null) continue;
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();

                if (message instanceof MetaMessage metaMessage) {
                    if (metaMessage.getType() == 0x51) { // Set Tempo
                        byte[] data = metaMessage.getData();
                        if (data.length >= 3) {
                            int mspqn = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                            if (mspqn > 0) {
                                tempoChanges.add(new Song.TempoChange(event.getTick(), mspqn));
                            }
                        }
                    }
                } else if (message instanceof ShortMessage sm) {
                    int channel = sm.getChannel();
                    int command = sm.getCommand();
                    int data1 = sm.getData1();
                    int data2 = sm.getData2();

                    if (command == ShortMessage.PROGRAM_CHANGE) {
                        channelInstruments[channel] = data1;
                    } else if (command == ShortMessage.NOTE_ON && data2 > 0) {
                        long midiTick = event.getTick();
                        if (midiTick > maxMidiTick) maxMidiTick = midiTick;
                        int program = channelInstruments[channel];
                        rawNotes.add(makeNoteData(midiTick, channel, data1, data2, program));
                    }
                }
            }
        }

        // If no notes, return empty song
        if (rawNotes.isEmpty()) {
            Song emptySong = new Song();
            String name = midiFile.getName().substring(0, midiFile.getName().lastIndexOf('.'));
            emptySong.fileName = midiFile.getName();
            emptySong.name = name;
            emptySong.displayName = name;
            emptySong.length = 0;
            emptySong.tempo = tempo;
            emptySong.notes = new long[0];
            return emptySong;
        }

        // Build tempo timeline
        tempoChanges.sort(Comparator.comparingLong(tc -> tc.midiTick));
        // Ensure first tempo change at tick 0
        if (tempoChanges.isEmpty() || tempoChanges.get(0).midiTick > 0) {
            tempoChanges.add(0, new Song.TempoChange(0, 500000)); // 120 BPM default
        }

        // Binary search for microseconds at a given tick
        LongFunction<Long> tickToMicros = (midiTick) -> {
            double micros = 0;
            long prevTick = 0;
            long prevMspqn = tempoChanges.get(0).mspqn;
            for (int i = 1; i < tempoChanges.size(); i++) {
                Song.TempoChange tc = tempoChanges.get(i);
                if (tc.midiTick >= midiTick) {
                    long deltaTicks = midiTick - prevTick;
                    micros += deltaTicks * prevMspqn / ppq;
                    return (long) micros;
                }
                long deltaTicks = tc.midiTick - prevTick;
                micros += deltaTicks * prevMspqn / ppq;
                prevTick = tc.midiTick;
                prevMspqn = tc.mspqn;
            }
            // After last tempo change
            long deltaTicks = midiTick - prevTick;
            micros += deltaTicks * prevMspqn / ppq;
            return (long) micros;
        };

        // Compute window-based octave offsets per channel (excluding percussion)
        double windowTicks = ppq * 2.0; // 2-beat windows
        int windowCount = (int) ((maxMidiTick / windowTicks) + 1);
        Map<Integer, Map<Integer, List<Integer>>> windowPitchMap = new HashMap<>();
        for (NoteData nd : rawNotes) {
            if (nd.channel() == PERCUSSION_CHANNEL) continue;
            int windowIdx = (int) (nd.midiTick() / windowTicks);
            windowPitchMap
                .computeIfAbsent(windowIdx, k -> new HashMap<>())
                .computeIfAbsent(nd.channel(), k -> new ArrayList<>())
                .add(nd.songPitch());
        }

        // Compute best octave offset per window per channel
        int lowBound = 54; // F#3
        int highBound = 78; // F#5
        int targetCenter = 66; // middle of range (F#4)
        Map<Integer, Map<Integer, Integer>> windowChannelOffset = new HashMap<>();
        for (var windowEntry : windowPitchMap.entrySet()) {
            int windowIdx = windowEntry.getKey();
            Map<Integer, List<Integer>> channelMap = windowEntry.getValue();
            Map<Integer, Integer> channelOffset = new HashMap<>();
            for (var channelEntry : channelMap.entrySet()) {
                int channel = channelEntry.getKey();
                List<Integer> pitches = channelEntry.getValue();
                int minPitch = 127;
                int maxPitch = 0;
                double sum = 0;
                for (int p : pitches) {
                    if (p < minPitch) minPitch = p;
                    if (p > maxPitch) maxPitch = p;
                    sum += p;
                }
                double average = sum / pitches.size();
                int bestOffset = 0;
                int bestViolation = Integer.MAX_VALUE;
                double bestCenterDist = Double.MAX_VALUE;
                for (int oct = -4; oct <= 4; oct++) {
                    int offset = oct * 12;
                    int shiftedMin = minPitch + offset;
                    int shiftedMax = maxPitch + offset;
                    int violation = 0;
                    if (shiftedMin < lowBound) violation += lowBound - shiftedMin;
                    if (shiftedMax > highBound) violation += shiftedMax - highBound;
                    double shiftedAvg = average + offset;
                    double centerDist = Math.abs(shiftedAvg - targetCenter);
                    if (violation < bestViolation || (violation == bestViolation && centerDist < bestCenterDist)) {
                        bestViolation = violation;
                        bestCenterDist = centerDist;
                        bestOffset = offset;
                    }
                }
                channelOffset.put(channel, bestOffset);
            }
            windowChannelOffset.put(windowIdx, channelOffset);
        }

        // Second pass: convert notes to noteLongs with octave adjustment
        List<Long> noteLongs = new ArrayList<>();
        for (NoteData nd : rawNotes) {
            long midiTick = nd.midiTick();
            int channel = nd.channel();
            int originalPitch = nd.songPitch(); // 已含 ONBS 的音色八度补偿 / 打击乐音高

            // Apply window-specific octave offset
            int adjustedPitch = originalPitch;
            if (channel != PERCUSSION_CHANNEL) {
                int windowIdx = (int) (midiTick / windowTicks);
                var channelOffsetMap = windowChannelOffset.get(windowIdx);
                if (channelOffsetMap != null) {
                    Integer offset = channelOffsetMap.get(channel);
                    if (offset != null) {
                        adjustedPitch = originalPitch + offset;
                        if (adjustedPitch < 0) adjustedPitch = 0;
                        if (adjustedPitch > 127) adjustedPitch = 127;
                    }
                }
            }

            // Convert adjusted pitch to Minecraft note ID
            int note = adjustedPitch;
            while (note < 54) note += 12;
            while (note > 78) note -= 12;
            int noteId = note - 54;

            NoteBlockInstrument instrument = nd.instrument();
            // NBS 乐器编号 ≠ Java 枚举 ordinal()：写进 song 数据必须用 Note.nbsId()，
            // 否则播放侧按 NBS 顺序索引会整体错位（如 Snare 放成 Bass Drum）。
            int instrumentId = Note.nbsId(instrument);

            // Convert MIDI tick to song tick (50ms per tick) with variable tempo
            long microseconds = tickToMicros.apply(midiTick);
            double timeInMs = microseconds / 1000.0;
            int songTick = (int) Math.round(timeInMs / 50.0);

            short layer = 0;

            long noteLong = (long) songTick | (long) layer << Note.LAYER_SHIFT | (long) instrumentId << Note.INSTRUMENT_SHIFT | (long) noteId << Note.NOTE_SHIFT;
            noteLongs.add(noteLong);
        }

        // Sort notes by tick
        noteLongs.sort(Comparator.comparingInt(n -> (short)(long)n));

        String name = midiFile.getName().substring(0, midiFile.getName().lastIndexOf('.'));
        short length = noteLongs.isEmpty() ? 0 : (short) (int) (noteLongs.get(noteLongs.size() - 1) & 0xFFFF);

        // Recalculate tempo based on actual duration
        if (maxMidiTick > 0) {
            long totalMicroseconds = tickToMicros.apply(maxMidiTick);
            double totalSeconds = totalMicroseconds / 1_000_000.0;
            double ticksPerSecond = length / totalSeconds;
            tempo = (short) Math.round(ticksPerSecond * 100.0);
        }

        // Create Song instance
        Song song = new Song();
        song.fileName = midiFile.getName();
        song.name = name;
        song.displayName = name;
        song.length = length;
        song.midiPpq = ppq;
        song.tempoChanges = tempoChanges;
        song.tempo = tempo;

        song.notes = noteLongs.stream().mapToLong(Long::longValue).toArray();

        // Populate uniqueNotes
        Set<Note> seen = new HashSet<>();
        for (long noteLong : noteLongs) {
            byte instrumentId = (byte) (noteLong >> Note.INSTRUMENT_SHIFT);
            byte noteId = (byte) (noteLong >> Note.NOTE_SHIFT);
            Note note = new Note(Note.instrumentById(instrumentId), noteId);
            if (seen.add(note)) {
                song.uniqueNotes.add(note);
            }
        }

        return song;
    }

}
