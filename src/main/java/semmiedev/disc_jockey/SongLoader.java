package semmiedev.disc_jockey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import semmiedev.disc_jockey.gui.SongListWidget;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SongLoader {
    public static final ArrayList<Song> SONGS = new ArrayList<>();
    public static final ArrayList<SongFolder> FOLDERS = new ArrayList<>();
    public static final ArrayList<String> SONG_SUGGESTIONS = new ArrayList<>();
    public static volatile boolean loadingSongs;
    public static volatile boolean showToast;

    /** 发布锁：SONGS/FOLDERS/SONG_SUGGESTIONS 只在锁内整体替换，避免 GUI 线程读到只填了一半的列表 */
    private static final Object PUBLISH_LOCK = new Object();
    /** 扫描并行度上限，实际并行度 = min(该值, CPU 核心数) */
    private static final int MAX_SCAN_THREADS = 8;
    /**
     * 增量扫描缓存：绝对路径 -> 上次扫描的 (lastModified, length, Song)。
     * 只由扫描线程（loadSongs 那个线程）读写，所以不需要同步；私有字段，不进入任何公开 API。
     * static 字段本身不参与 Java 序列化，所以不需要 transient。
     */
    private static final Map<String, CachedSong> SONG_CACHE = new HashMap<>();

    private static final class CachedSong {
        final long lastModified;
        final long length;
        final Song song;

        CachedSong(long lastModified, long length, Song song) {
            this.lastModified = lastModified;
            this.length = length;
            this.song = song;
        }
    }

    /** 目录遍历阶段收集到的一个待处理文件（此时只做了 stat，没有读文件内容） */
    private static final class PendingFile {
        final File file;
        /** 直接包含该文件的目录；歌曲根目录下的文件为 null（对应 Song.folder == null 的语义） */
        final SongFolder folder;
        /** 缓存 key：绝对路径 */
        final String key;
        final long lastModified;
        final long length;

        PendingFile(File file, SongFolder folder) {
            this.file = file;
            this.folder = folder;
            this.key = file.getAbsolutePath();
            this.lastModified = file.lastModified();
            this.length = file.length();
        }
    }

    public static class SongFolder {
        public final String name;
        public final String path;
        public final ArrayList<Song> songs = new ArrayList<>();
        public final ArrayList<SongFolder> subFolders = new ArrayList<>();
        public SongListWidget.FolderEntry entry;

        public SongFolder(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    public static void loadSongs() {
        if (loadingSongs) return;
        // 先置位再起线程：连续两次刷新不会并发扫描（缓存 map 与发布流程都假定只有一个扫描线程）
        loadingSongs = true;

        // 与原实现一致：扫描期间帮助命令只提示"正在加载"（只动补全列表，不动 SONGS/FOLDERS）
        synchronized (PUBLISH_LOCK) {
            SONG_SUGGESTIONS.clear();
            SONG_SUGGESTIONS.add("Songs are loading, please wait");
        }

        new Thread(() -> {
            try {
                long startedAt = System.nanoTime();

                // 1) 目录遍历 + stat（很快）：只收集候选文件，并同时把新的目录树搭好
                ArrayList<PendingFile> pending = new ArrayList<>();
                ArrayList<SongFolder> rootFolders = new ArrayList<>();
                collectSongs(Main.songsFolder, null, pending, rootFolders);

                // 2) 增量判定：mtime + size 都没变 -> 直接复用上次的 Song 对象，不再读文件
                Song[] parsed = new Song[pending.size()];
                ArrayList<Integer> toParse = new ArrayList<>();
                int reused = 0;
                for (int i = 0; i < pending.size(); i++) {
                    PendingFile pendingFile = pending.get(i);
                    CachedSong cached = SONG_CACHE.get(pendingFile.key);
                    if (cached != null && cached.lastModified == pendingFile.lastModified && cached.length == pendingFile.length) {
                        parsed[i] = cached.song;
                        reused++;
                    } else {
                        toParse.add(i);
                    }
                }

                // 3) 未命中的文件并行解析（只读元数据；MIDI 只建占位，见 parseSong）
                parseAll(pending, toParse, parsed);

                // 4) 组装：挂到新目录树上、按最终顺序重建 entry、生成补全列表
                ArrayList<Song> songs = new ArrayList<>(pending.size());
                ArrayList<String> suggestions = new ArrayList<>(pending.size());
                for (int i = 0; i < pending.size(); i++) {
                    Song song = parsed[i];
                    if (song == null) continue;
                    PendingFile pendingFile = pending.get(i);
                    if (pendingFile.folder == null) {
                        // 复用的对象可能还挂在上一轮的目录对象上，根目录歌曲必须为 null
                        song.folder = null;
                    } else {
                        song.folder = pendingFile.folder;
                        pendingFile.folder.songs.add(song);
                    }
                    songs.add(song);
                }
                for (int i = 0; i < songs.size(); i++) makeEntry(songs.get(i), i);
                for (Song song : songs) suggestions.add(song.displayName);

                // 5) 一次性发布
                synchronized (PUBLISH_LOCK) {
                    SONGS.clear();
                    SONGS.addAll(songs);
                    FOLDERS.clear();
                    FOLDERS.addAll(rootFolders);
                    SONG_SUGGESTIONS.clear();
                    SONG_SUGGESTIONS.addAll(suggestions);
                }

                // 6) 缓存整体换成这一轮的结果：被删除的文件自然失效，新增/改动的文件已经重新解析
                SONG_CACHE.clear();
                for (int i = 0; i < pending.size(); i++) {
                    if (parsed[i] == null) continue; // 解析失败的下次刷新重试
                    PendingFile pendingFile = pending.get(i);
                    SONG_CACHE.put(pendingFile.key, new CachedSong(pendingFile.lastModified, pendingFile.length, parsed[i]));
                }

                // 收藏过滤：先建一次文件名集合再筛，避免 favorites × songs 的 O(n²) 扫描
                HashSet<String> loadedFileNames = new HashSet<>();
                for (Song song : SONGS) loadedFileNames.add(song.fileName);
                Main.config.favorites.removeIf(favorite -> !loadedFileNames.contains(favorite));

                // 26.2 保持基线写法：Minecraft.getInstance().gui.toastManager() / SystemToast.SystemToastId /
                // Component / Minecraft.getInstance().font 字段（1.21.11 版对应 MinecraftClient#getToastManager()、
                // SystemToast.Type、Text、textRenderer，移植时未改回）
                if (showToast && Minecraft.getInstance().font != null) SystemToast.add(Minecraft.getInstance().gui.toastManager(), SystemToast.SystemToastId.PACK_LOAD_FAILURE, Main.NAME, Component.translatable(Main.MOD_ID+".loading_done"));
                showToast = true;

                Main.LOGGER.info("Song list loaded: {} songs ({} parsed, {} cached) in {} ms",
                        songs.size(), toParse.size(), reused, (System.nanoTime() - startedAt) / 1000000L);
            } catch (Throwable throwable) {
                // 扫描失败也必须复位 loadingSongs，否则界面会永远停在"正在加载"
                Main.LOGGER.error("Unable to refresh song list", throwable);
            } finally {
                loadingSongs = false;
            }
        }, "disc-jockey-song-loader").start();
    }

    /**
     * 遍历目录并搭好新的 SongFolder 树（顺序与逐文件递归的旧实现完全一致）：
     * 每个目录在自己的位置创建 SongFolder，子目录在递归返回时挂到父目录的 subFolders，
     * 根目录的 subFolders 就是 FOLDERS。文件只记录 stat 结果，不读内容。
     */
    private static void collectSongs(File folder, SongFolder parentFolder, ArrayList<PendingFile> pending, ArrayList<SongFolder> rootFolders) {
        if (!folder.isDirectory()) return;

        SongFolder songFolder = new SongFolder(folder.getName(), folder.getPath());

        File[] files = folder.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                collectSongs(file, songFolder, pending, rootFolders);
            } else if (isSongFile(file)) {
                pending.add(new PendingFile(file, parentFolder == null ? null : songFolder));
            }
        }

        if (parentFolder == null) {
            rootFolders.addAll(songFolder.subFolders);
        } else {
            parentFolder.subFolders.add(songFolder);
        }
    }

    /**
     * 扩展名预筛：只处理 NBS / MIDI。完全没有扩展名的文件仍按 NBS 尝试解析（兜底，避免漏掉无扩展名的 .nbs），
     * 其它已知扩展名（.txt/.png/.DS_Store 之类）直接跳过——旧实现对目录里每个文件都尝试解析，会刷一堆错误日志。
     */
    private static boolean isSongFile(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".nbs") || name.endsWith(".mid") || name.endsWith(".midi")) return true;
        return name.lastIndexOf('.') < 0;
    }

    /** 用有界线程池并行解析未命中缓存的文件；结果按 pending 的下标写回，保证顺序确定 */
    private static void parseAll(ArrayList<PendingFile> pending, ArrayList<Integer> toParse, Song[] parsed) {
        if (toParse.isEmpty()) return;

        int threads = Math.max(1, Math.min(MAX_SCAN_THREADS, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "disc-jockey-song-scan");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ArrayList<Future<?>> futures = new ArrayList<>(toParse.size());
            for (int index : toParse) {
                File file = pending.get(index).file;
                // 用块体 lambda：只匹配 Runnable 重载，避免 submit(Runnable)/submit(Callable) 的重载歧义
                futures.add(pool.submit(() -> {
                    parsed[index] = parseQuietly(file);
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    Main.LOGGER.error("Unable to parse song", e.getCause());
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private static Song parseQuietly(File file) {
        try {
            return parseSong(file, false);
        } catch (Exception exception) {
            Main.LOGGER.error("Unable to read or parse song {}", file.getName(), exception);
            return null;
        }
    }

    public static Song loadSong(File file) throws IOException {
        return loadSong(file, true);
    }

    public static Song loadSong(File file, boolean loadNotes) throws IOException {
        Song song = parseSong(file, loadNotes);
        if (song != null) makeEntry(song, SONGS.size());
        return song;
    }

    /** entry 的 index 是 final，所以每次发布/加载都要按最终位置重建；favorite 也跟着配置重算 */
    private static void makeEntry(Song song, int index) {
        song.entry = new SongListWidget.SongEntry(song, index);
        song.entry.favorite = Main.config.favorites.contains(song.fileName);
    }

    private static Song parseSong(File file, boolean loadNotes) throws IOException {
        if (!file.isFile()) return null;

        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".mid") || fileName.endsWith(".midi")) {
            if (!loadNotes) {
                // MIDI 懒解析：解析 MIDI 要建整张 tempo map，是扫描里最慢的一步，所以扫描阶段只建占位对象；
                // name/notes/length/tempo/midiPpq/tempoChanges 由 ensureSongLoaded 在播放/试听/显示时长前补齐
                Song song = new Song();
                song.lazyMidi = true;
                song.fileName = file.getName().replaceAll("[\\n\\r]", "");
                song.filePath = file.getPath();
                song.name = "";
                song.displayName = midiDisplayName(song);
                song.searchableFileName = song.fileName.toLowerCase().replaceAll("\\s", "");
                song.searchableName = "";
                return song;
            }
            try {
                Song song = MidiLoader.loadFromMidi(file);
                song.lazyMidi = false;
                song.fileName = file.getName().replaceAll("[\\n\\r]", "");
                song.filePath = file.getPath();
                song.displayName = midiDisplayName(song);
                song.searchableFileName = song.fileName.toLowerCase().replaceAll("\\s", "");
                song.searchableName = song.name.toLowerCase().replaceAll("\\s", "");
                return song;
            } catch (Exception e) {
                throw new IOException("Failed to load MIDI file", e);
            }
        }

        InputStream inputStream = Files.newInputStream(file.toPath());
        try {
            BinaryReader reader = new BinaryReader(inputStream);
            Song song = new Song();

            song.fileName = file.getName().replaceAll("[\\n\\r]", "");
            song.filePath = file.getPath();

            song.length = reader.readShort();

            boolean newFormat = song.length == 0;
            if (newFormat) {
                song.formatVersion = reader.readByte();
                song.vanillaInstrumentCount = reader.readByte();
                song.length = reader.readShort();
            }

            song.height = reader.readShort();
            song.name = reader.readString().replaceAll("[\\n\\r]", "");
            song.author = reader.readString().replaceAll("[\\n\\r]", "");
            song.originalAuthor = reader.readString().replaceAll("[\\n\\r]", "");
            song.description = reader.readString().replaceAll("[\\n\\r]", "");
            song.tempo = reader.readShort();
            song.autoSaving = reader.readByte();
            song.autoSavingDuration = reader.readByte();
            song.timeSignature = reader.readByte();
            song.minutesSpent = reader.readInt();
            song.leftClicks = reader.readInt();
            song.rightClicks = reader.readInt();
            song.blocksAdded = reader.readInt();
            song.blocksRemoved = reader.readInt();
            song.importFileName = reader.readString().replaceAll("[\\n\\r]", "");

            if (newFormat) {
                song.loop = reader.readByte();
                song.maxLoopCount = reader.readByte();
                song.loopStartTick = reader.readShort();
            }

            song.displayName = song.name.replaceAll("\\s", "").isEmpty() ? song.fileName : song.name+" ("+song.fileName+")";
            song.searchableFileName = song.fileName.toLowerCase().replaceAll("\\s", "");
            song.searchableName = song.name.toLowerCase().replaceAll("\\s", "");

            if (!loadNotes) {
                return song;
            }

            short tick = -1;
            short jumps;
            ArrayList<Long> noteList = new ArrayList<>();
            HashSet<Note> uniqueSet = new HashSet<>();
            // 自定义乐器的起点（NBS 规范）：新格式用文件头记录的 vanillaInstrumentCount（ONBS 写 16，
            // 认识铜管乐器的版本会写 20），旧格式（classic）没有该字段，按标准 16 处理。
            // 取 max(文件值, 16) 是为了容忍把该字节写成旧约定 9 的文件：否则 10-15 号标准音色
            // 会被误判成自定义乐器而全部塌成 Harp。
            int vanillaInstrumentCount = newFormat
                    ? Math.max(song.vanillaInstrumentCount & 0xFF, Note.NBS_VANILLA_INSTRUMENT_COUNT)
                    : Note.NBS_VANILLA_INSTRUMENT_COUNT;
            int customInstrumentNotes = 0;
            while ((jumps = reader.readShort()) != 0) {
                tick += jumps;
                short layer = -1;
                while ((jumps = reader.readShort()) != 0) {
                    layer += jumps;

                    // NBS 里的乐器编号是 0-240 的无符号字节：>= 16 表示自定义乐器，数值会超过 127，
                    // 按有符号 byte 读会变成负数，所以这里先按无符号取。
                    int rawInstrumentId = reader.readByte() & 0xFF;
                    byte noteId = (byte)(reader.readByte() - 33);

                    if (newFormat) {
                        reader.readByte();
                        reader.readByte();
                        reader.readShort();
                    }

                    if (noteId < 0) {
                        noteId = 0;
                    } else if (noteId > 24) {
                        noteId = 24;
                    }

                    // 自定义乐器本模组没有音源，回落 Harp；越界编号也不会再索引 INSTRUMENTS（老版本在这里 AIOOBE）
                    int instrumentId = Note.resolveInstrumentId(rawInstrumentId, vanillaInstrumentCount);
                    if (instrumentId != rawInstrumentId) {
                        customInstrumentNotes++;
                    }

                    Note note = new Note(Note.instrumentById(instrumentId), noteId);
                    if (uniqueSet.add(note)) {
                        song.uniqueNotes.add(note);
                    }

                    // 乐器位存规整后的编号，播放/试听侧用 Note.instrumentById() 读，永远落在表内
                    long noteLong = tick | layer << Note.LAYER_SHIFT | (long)instrumentId << Note.INSTRUMENT_SHIFT | (long)noteId << Note.NOTE_SHIFT;
                    noteList.add(noteLong);
                }
            }
            song.notes = noteList.stream().mapToLong(Long::longValue).toArray();

            if (customInstrumentNotes > 0) {
                Main.LOGGER.info("Song \"{}\": {} of {} notes use custom instruments (NBS instrument id >= {}), which this mod cannot play - using Harp instead",
                        song.fileName, customInstrumentNotes, song.notes.length, vanillaInstrumentCount);
            }

            return song;
        } finally {
            // 扫描会连续打开大量歌曲文件，读完必须关闭输入流
            inputStream.close();
        }
    }

    /** MIDI 显示名规则（与 26.2 基线一致）：没有可用曲名时退回文件名 + " [midi]" */
    private static String midiDisplayName(Song song) {
        return song.name.replaceAll("\\s", "").isEmpty() ? (song.fileName.replaceAll("(?i)\\.midi?$", "") + " [midi]") : song.name + " [midi]";
    }

    public static void ensureSongLoaded(Song song) throws IOException {
        if (song.lazyMidi) {
            // 懒解析的 MIDI：现在才真正读 MIDI 文件并回填，播放/试听/时长显示都走这里
            loadLazyMidi(song);
            return;
        }
        if (song.notes != null && song.notes.length > 0) {
            return;
        }
        File songFile = new File(song.filePath);
        if (!songFile.exists()) {
            throw new IOException("Song file not found: " + song.filePath);
        }
        Song fullSong = loadSong(songFile, true);
        if (fullSong == null) {
            throw new IOException("Failed to load song: " + song.fileName);
        }
        song.notes = fullSong.notes;
        song.uniqueNotes.clear();
        song.uniqueNotes.addAll(fullSong.uniqueNotes);
    }

    private static void loadLazyMidi(Song song) throws IOException {
        File songFile = new File(song.filePath);
        if (!songFile.exists()) {
            throw new IOException("Song file not found: " + song.filePath);
        }

        Song fullSong;
        try {
            fullSong = MidiLoader.loadFromMidi(songFile);
        } catch (Exception e) {
            throw new IOException("Failed to load MIDI file", e);
        }

        String previousDisplayName = song.displayName;
        song.name = fullSong.name;
        song.displayName = midiDisplayName(song);
        song.searchableName = song.name.toLowerCase().replaceAll("\\s", "");
        song.length = fullSong.length;
        song.tempo = fullSong.tempo;
        song.midiPpq = fullSong.midiPpq;
        song.tempoChanges = fullSong.tempoChanges;
        song.notes = fullSong.notes;
        song.uniqueNotes.clear();
        song.uniqueNotes.addAll(fullSong.uniqueNotes);
        song.lazyMidi = false;

        // 列表项不缓存名字：SongEntry.extractContent() 直接读 song.displayName（同一个 Song 实例），这里无需同步。
        // 注意 SongEntry 没有 displayName 字段，不要写 song.entry.displayName = ...（编译会报"找不到符号"）。

        // 补全列表里记的是扫描时的占位名，这里替换成解析后的真实显示名，
        // 否则 /discjockey play <TAB> 补出来的名字会和 DiscjockeyCommand 按 displayName 的匹配对不上
        synchronized (PUBLISH_LOCK) {
            int index = SONG_SUGGESTIONS.indexOf(previousDisplayName);
            if (index >= 0 && !previousDisplayName.equals(song.displayName)) {
                SONG_SUGGESTIONS.set(index, song.displayName);
            }
        }
    }

    public static void sort() {
        SONGS.sort(Comparator.comparing(song -> song.displayName));
        FOLDERS.sort(Comparator.comparing(folder -> folder.name));
        for (SongFolder folder : FOLDERS) {
            folder.songs.sort(Comparator.comparing(song -> song.displayName));
            folder.subFolders.sort(Comparator.comparing(subFolder -> subFolder.name));
        }
    }
}
