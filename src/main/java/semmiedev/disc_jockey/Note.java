package semmiedev.disc_jockey;

import java.util.HashMap;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

public record Note(NoteBlockInstrument instrument, byte note) {
    public static final HashMap<NoteBlockInstrument, Block> INSTRUMENT_BLOCKS = new HashMap<>();

//    static List<Block> copperBlock = List.of(
//            Blocks.COPPER_BLOCK,
//            Blocks.CHISELED_COPPER,
//            Blocks.CUT_COPPER,
//            Blocks.CUT_COPPER_STAIRS,
//            Blocks.CUT_COPPER_SLAB
//    );
//    static List<Block> exposedCopperBlock = List.of(
//            Blocks.EXPOSED_COPPER,
//            Blocks.EXPOSED_CHISELED_COPPER,
//            Blocks.EXPOSED_CUT_COPPER,
//            Blocks.EXPOSED_CUT_COPPER_STAIRS,
//            Blocks.EXPOSED_CUT_COPPER_SLAB
//    );
//    static List<Block> weatheredCopperBlock = List.of(
//            Blocks.WEATHERED_COPPER,
//            Blocks.WEATHERED_CHISELED_COPPER,
//            Blocks.WEATHERED_CUT_COPPER,
//            Blocks.WEATHERED_CUT_COPPER_STAIRS,
//            Blocks.WEATHERED_CUT_COPPER_SLAB
//    );
//    static List<Block> oxidizedCopperBlock = List.of(
//            Blocks.OXIDIZED_COPPER,
//            Blocks.OXIDIZED_CHISELED_COPPER,
//            Blocks.OXIDIZED_CUT_COPPER,
//            Blocks.OXIDIZED_CUT_COPPER_STAIRS,
//            Blocks.OXIDIZED_CUT_COPPER_SLAB
//    );

    public static final byte LAYER_SHIFT = Short.SIZE;
    public static final byte INSTRUMENT_SHIFT = Short.SIZE * 2;
    public static final byte NOTE_SHIFT = Short.SIZE * 2 + Byte.SIZE;

    public static final NoteBlockInstrument[] INSTRUMENTS = new NoteBlockInstrument[]{
            NoteBlockInstrument.HARP,
            NoteBlockInstrument.BASS,
            NoteBlockInstrument.BASEDRUM,
            NoteBlockInstrument.SNARE,
            NoteBlockInstrument.HAT,
            NoteBlockInstrument.GUITAR,
            NoteBlockInstrument.FLUTE,
            NoteBlockInstrument.BELL,
            NoteBlockInstrument.CHIME,
            NoteBlockInstrument.XYLOPHONE,
            NoteBlockInstrument.IRON_XYLOPHONE,
            NoteBlockInstrument.COW_BELL,
            NoteBlockInstrument.DIDGERIDOO,
            NoteBlockInstrument.BIT,
            NoteBlockInstrument.BANJO,
            NoteBlockInstrument.PLING,
            NoteBlockInstrument.TRUMPET,
            NoteBlockInstrument.TRUMPET_EXPOSED,
            NoteBlockInstrument.TRUMPET_WEATHERED,
            NoteBlockInstrument.TRUMPET_OXIDIZED
    };

    /**
     * NBS 规范里“原版乐器”的个数：文件中的乐器编号 0-15 是标准音色（0=Harp, 1=Double Bass,
     * 2=Bass Drum, 3=Snare, 4=Click … 15=Pling），<b>&gt;= 16 一律表示自定义乐器</b>
     * （自定义乐器表是文件末尾可选的第 4 段，见 https://noteblock.studio/nbs ）。
     *
     * <p>注意：下面 {@link #INSTRUMENTS} 里 16-19 的 4 个铜管乐器是 26.2 才有的扩展音色，
     * <b>不在</b> NBS 的标准 16 音色里 —— 只有当 .nbs 文件自己声明 vanillaInstrumentCount &gt;= 20 时
     * 才会用到它们；MIDI 转歌曲时不会写这几个编号（见 {@link #nbsId}）。
     */
    public static final int NBS_VANILLA_INSTRUMENT_COUNT = 16;

    /**
     * 自定义乐器（本模组没有它的音源文件，放不出原声）以及任何未知/越界编号统一回落到的音色：Harp。
     * 与 {@link #nbsId} 的“未知按 Harp 处理”保持一致。
     */
    public static final int FALLBACK_INSTRUMENT_ID = 0;

    /**
     * NBS 乐器编号（0=Harp, 1=Double Bass, 2=Bass Drum, 3=Snare, 4=Click … 15=Pling；
     * 16-19 是 26.2 新增的 4 个铜管乐器在本表里的扩展编号），即 {@link #INSTRUMENTS} 的下标。
     * 写入歌曲数据（noteLong 的乐器位）用的就是这个编号，
     * <b>不是</b> {@link NoteBlockInstrument#ordinal()} —— 两者顺序不同，用错会导致所有音色错位。
     *
     * <p>只在前 {@link #NBS_VANILLA_INSTRUMENT_COUNT} 项里查找：NBS 文件里编号 &gt;= 16 的含义是
     * “自定义乐器”，所以 4 个铜管乐器（16-19）<b>不能</b>写进歌曲数据 —— 写进去的歌曲在任何
     * 按 NBS 规范读取的工具/模组里都会被当成自定义乐器（旧版实现正是因此把铜管写成了 16-19）。
     */
    public static int nbsId(NoteBlockInstrument instrument) {
        for (int i = 0; i < NBS_VANILLA_INSTRUMENT_COUNT && i < INSTRUMENTS.length; i++) {
            if (INSTRUMENTS[i] == instrument) return i;
        }
        return FALLBACK_INSTRUMENT_ID; // 未知乐器（含铜管）按 Harp 处理
    }

    /**
     * 把 .nbs 文件里读到的乐器编号解析成 {@link #INSTRUMENTS} 的合法下标。
     *
     * <p>规则来自 NBS 规范：编号 &lt; {@code vanillaCount}（文件头里记录的 vanillaInstrumentCount）
     * 才是原版音色，其余都是自定义乐器。自定义乐器本模组无法发声，统一回落 {@link #FALLBACK_INSTRUMENT_ID}，
     * 而不是拿它去索引 {@link #INSTRUMENTS}（老版本 “Index 21 out of bounds for length 16” 崩溃的原因）；
     * 26.2 的表有 20 项，所以声明了 20 个原版音色的文件里的 16-19 号（铜管）能正常映射，
     * 而按 NBS 标准声明 16 个的文件里的 16-19 号会（正确地）落到自定义乐器分支。
     *
     * @param rawId        文件里的乐器编号（0-240，已按无符号处理，见 SongLoader#parseSong）
     * @param vanillaCount 该文件里原版音色的个数
     */
    public static int resolveInstrumentId(int rawId, int vanillaCount) {
        if (rawId >= 0 && rawId < vanillaCount && rawId < INSTRUMENTS.length) return rawId;
        return FALLBACK_INSTRUMENT_ID;
    }

    /**
     * 播放/试听等热路径取音色：任何越界编号都回落 Harp，保证不会再抛 ArrayIndexOutOfBoundsException。
     * 正常的音符数据在解析时已经过 {@link #resolveInstrumentId} 规整，这里只是兜底。
     */
    public static NoteBlockInstrument instrumentById(int id) {
        if (id >= 0 && id < INSTRUMENTS.length) return INSTRUMENTS[id];
        return INSTRUMENTS[FALLBACK_INSTRUMENT_ID];
    }

    static {
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.HARP, Blocks.AIR);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BASEDRUM, Blocks.STONE);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.SNARE, Blocks.SAND);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.HAT, Blocks.GLASS);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BASS, Blocks.OAK_PLANKS);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.FLUTE, Blocks.CLAY);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BELL, Blocks.GOLD_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.GUITAR, Blocks.WOOL.pick(DyeColor.WHITE));
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.CHIME, Blocks.PACKED_ICE);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.XYLOPHONE, Blocks.BONE_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.IRON_XYLOPHONE, Blocks.IRON_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.COW_BELL, Blocks.SOUL_SAND);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.DIDGERIDOO, Blocks.PUMPKIN);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BIT, Blocks.EMERALD_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.BANJO, Blocks.HAY_BLOCK);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.PLING, Blocks.GLOWSTONE);
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET, Blocks.COPPER_BLOCK.weathering().pick(WeatheringCopper.WeatherState.UNAFFECTED));
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET_EXPOSED, Blocks.COPPER_BLOCK.weathering().pick(WeatheringCopper.WeatherState.EXPOSED));
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET_WEATHERED, Blocks.COPPER_BLOCK.weathering().pick(WeatheringCopper.WeatherState.WEATHERED));
        INSTRUMENT_BLOCKS.put(NoteBlockInstrument.TRUMPET_OXIDIZED, Blocks.COPPER_BLOCK.weathering().pick(WeatheringCopper.WeatherState.OXIDIZED));
    }
}
