package semmiedev.disc_jockey;

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * ONBS（Open Note Block Studio）的 MIDI → NBS 音色映射表，逐条照抄自：
 * https://github.com/OpenNBS/NoteBlockStudio  scripts/midi_instruments/midi_instruments.gml
 *
 *   midi_ins[program, 1] = NBS 乐器编号（0=Harp, 1=Double Bass, 2=Bass Drum, 3=Snare, 4=Click … 15=Pling）
 *   midi_ins[program, 2] = 八度补偿，写入歌曲时 note += 12 * 该值（见 import_midi.gml）
 *   midi_drum[note, 1]  = MIDI 打击乐音高 note(24-87) 对应的 NBS 乐器编号
 *   midi_drum[note, 2]  = 该打击乐音高对应的歌曲音高（NBS 侧会 +33 换算到 0-87 音阶，见 open_midi.gml）
 *
 * 这样本模组从 MIDI 转换出的音色/音区，与用 NBS 导入同一个 MIDI 的结果一致；
 * 注意 NBS 乐器编号 ≠ NoteBlockInstrument.ordinal()，写入 song 数据必须用 Note.nbsId()。
 *
 * 【与 ONBS 保持一致，不随版本偏离】26.2 的 Note.INSTRUMENTS 末尾多了 4 个铜管乐器
 * （16-19），但 NBS 规范里编号 &gt;= 16 表示<b>自定义乐器</b>，所以铜管族 56-63 仍然走 ONBS 的
 * PROGRAM_ID / PROGRAM_OCTAVE（回退到 Flute 6 / Didgeridoo 12），不写 16-19 这几个编号。
 * 这样同一个 MIDI 在本模组和 NBS 里得到的音色/音区完全一致，也不会和文件里的自定义乐器撞号。
 * PROGRAM_ID / PROGRAM_OCTAVE / DRUM_ID / DRUM_PITCH 四个数据表与 ONBS 逐字节一致。
 */
final class OnbsMidiInstruments {
    private OnbsMidiInstruments() {}

    /** GM program(0-127) → NBS 乐器编号 */
    private static final byte[] PROGRAM_ID = {
            0, 15, 15, 15, 0, 0, 5, 14, 7, 7, 7, 10, 10, 9, 7, 5,
            6, 10, 6, 6, 6, 6, 6, 6, 5, 5, 0, 5, 1, 12, 12, 5,
            1, 1, 1, 1, 5, 5, 1, 15, 6, 6, 6, 6, 6, 1, 0, 3,
            6, 6, 6, 6, 6, 6, 6, 3, 6, 6, 6, 12, 6, 12, 12, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            13, 6, 6, 6, 5, 6, 6, 1, 7, 6, 6, 6, 6, 6, 6, 8,
            8, 6, 8, 5, 15, 6, 6, 5, 14, 14, 14, 5, 10, 6, 6, 6,
            8, 11, 10, 9, 2, 3, 3, 8, 4, 6, 8, 6, 7, 2, 3, 3,
    };

    /** GM program(0-127) → 八度补偿（照抄 ONBS midi_ins[.,2]，逐项一致，含铜管 56-63） */
    private static final byte[] PROGRAM_OCTAVE = {
            0, 0, 0, 0, 0, 0, 1, 0, -2, -2, -2, 0, 0, -2, -2, 1,
            -1, 0, -1, -1, -1, -1, -1, -1, 1, 1, 0, 1, 2, 2, 2, 3,
            2, 2, 2, 2, 1, 1, 2, 0, -1, -1, -1, -1, -1, 2, 0, 0,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, -1, 2, 2, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            0, -1, -1, -1, 1, -1, -1, 2, -2, -1, -1, -1, -1, -1, -1, -2,
            -2, -1, -2, 1, 0, -1, -1, 1, 0, 0, 0, 1, 0, -1, -1, -1,
            -2, -1, 0, -2, 0, 0, 0, -2, 1, -1, -2, 1, 2, 0, 0, 0,
    };

    /** MIDI 打击乐音高(0-127) → NBS 乐器编号，-1 表示 ONBS 未收录（回退 Click） */
    private static final byte[] DRUM_ID = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, 13, 3, 4, 3, 3, 4, 4, 4,
            4, 4, 8, 2, 2, 4, 3, 4, 3, 2, 3, 2, 3, 2, 3, 2,
            2, 3, 2, 3, 3, 3, 4, 3, 11, 3, 4, 3, 4, 4, 4, 2,
            2, 3, 3, 9, 9, 4, 4, 6, 6, 4, 4, 4, 4, 4, 12, 12,
            4, 8, 3, 8, 8, 4, 2, 2, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    };

    /** MIDI 打击乐音高(0-127) → ONBS 的歌曲音高（未收录为 0） */
    private static final byte[] DRUM_PITCH = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 39, 8, 25, 18, 27, 16, 13, 9,
            6, 2, 17, 10, 6, 6, 8, 6, 4, 6, 22, 13, 22, 15, 18, 20,
            23, 17, 23, 24, 8, 13, 18, 18, 5, 13, 2, 13, 9, 2, 8, 22,
            15, 13, 8, 12, 5, 20, 23, 34, 33, 17, 11, 18, 10, 5, 25, 26,
            16, 19, 22, 6, 15, 21, 14, 7, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    /** GM 音色名，仅用于注释/排查（与 PROGRAM_ID 一一对应） */
    private static final String[] PROGRAM_NAME = {
            "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
            "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
            "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
            "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
            "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
            "Reed Organ", "Accordion", "Harmonica", "Bandoneon",
            "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
            "Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
            "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
            "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
            "Violin", "Viola", "Cello", "Contrabass",
            "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
            "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
            "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
            "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
            "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
            "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
            "Oboe", "English Horn", "Bassoon", "Clarinet",
            "Piccolo", "Flute", "Recorder", "Pan Flute",
            "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
            "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
            "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
            "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
            "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
            "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
            "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
            "Sitar", "Banjo", "Shamisen", "Koto",
            "Kalimba", "Bag pipe", "Fiddle", "Shanai",
            "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
            "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
            "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
            "Telephone Ring", "Helicopter", "Applause", "Gunshot",
    };

    static NoteBlockInstrument instrumentForProgram(int program) {
        // 铜管族 56-63 与其它音色一样走 ONBS 的 PROGRAM_ID：ONBS 把它回退成 Flute(6)/Didgeridoo(12)，
        // 本模组保持同样结果（NBS 编号 16-19 属于“自定义乐器”区，不能拿来当铜管编号用）。
        if (program < 0 || program >= PROGRAM_ID.length) return NoteBlockInstrument.HARP;
        return Note.instrumentById(PROGRAM_ID[program] & 0xFF);
    }

    static int octaveForProgram(int program) {
        if (program < 0 || program >= PROGRAM_OCTAVE.length) return 0;
        return PROGRAM_OCTAVE[program];
    }

    /** ONBS 的打击乐乐器；未收录时回退 Click（与 NBS 的默认表现一致） */
    static NoteBlockInstrument drumInstrument(int midiNote) {
        if (midiNote < 0 || midiNote >= DRUM_ID.length) return NoteBlockInstrument.HAT;
        int id = DRUM_ID[midiNote];
        if (id < 0) return NoteBlockInstrument.HAT;
        return Note.instrumentById(id & 0xFF);
    }

    /** ONBS 的打击乐歌曲音高（0-87 音阶，0 = MIDI 21）；未收录时给中间值 33 */
    static int drumSongPitch(int midiNote) {
        if (midiNote < 0 || midiNote >= DRUM_ID.length || DRUM_ID[midiNote] < 0) return 33;
        return (DRUM_PITCH[midiNote] & 0xFF) + 33;
    }

    /** 排查用：program 的 GM 名称 */
    static String programName(int program) {
        if (program < 0 || program >= PROGRAM_NAME.length) return "n/a";
        return PROGRAM_NAME[program];
    }
}
