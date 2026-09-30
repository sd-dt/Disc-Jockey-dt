package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

public class SongPlayer implements ClientTickEvents.StartLevelTick {
    private static boolean warned;
    public boolean running;
    public Song song;

    private int index;
    private double tick; // Aka song position
    private long lastPlaybackTickAt = Util.TIMESTAMP_UNINITIALIZED;
    // The thread executing the tickPlayback method
    private Thread playbackThread = null;
    public long playbackLoopDelay = 5;
    private final Object playbackLock = new Object();
    // Just for external debugging purposes
    // 播放线程读取、UI / 命令线程写入，用 volatile 保证可见性；写入请走 setSpeed()（自动夹到 0.1x–20x）
    public volatile float speed = 1.0f;
    public boolean didSongReachEnd = false;
    public boolean loopSong = false;
    /** 播放速率的合法范围（含端点），GUI 滑块与 /discjockey speed 都用这组常量 */
    public static final float MIN_SPEED = 0.1f;
    public static final float MAX_SPEED = 20.0f;

    public enum PlayMode {
        SINGLE_LOOP,
        LIST_LOOP,
        RANDOM,
        STOP_AFTER
    }

    private PlayMode playMode = PlayMode.STOP_AFTER;
    private int randomIndex = -1;
    private final RateLimiter rateLimiter = new RateLimiter();
    public final Tuner tuner = new Tuner();

    public SongPlayer() {
        Main.TICK_LISTENERS.add(this);
    }

    public float getSpeed() {
        return speed;
    }

    /**
     * 设置播放速率：非有限值（NaN / ±Infinity）回退到 1.0f，其余夹到 [MIN_SPEED, MAX_SPEED]。
     * 播放算式（tickPlayback 里 tick += ... * speed）保持原样，不做第二处 clamp / 取整。
     */
    public void setSpeed(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 1.0f;
        this.speed = Math.clamp(value, MIN_SPEED, MAX_SPEED);
    }

    public synchronized void startPlaybackThread() {
        if (Main.config.disableAsyncPlayback) {
            playbackThread = null;
            return;
        }

        this.playbackThread = new Thread(() -> {
            while (true) {
                tickPlayback();
                synchronized (playbackLock) {
                    try {
                        playbackLock.wait(playbackLoopDelay);
                    } catch (InterruptedException ignored) {}
                }
            }
        });
        this.playbackThread.start();
    }

    public synchronized void stopPlaybackThread() {
        this.playbackThread = null;
        synchronized (playbackLock) {
            playbackLock.notify();
        }
    }

    public synchronized void start(Song song) {
        if (!Main.config.hideWarning && !warned) {
            Minecraft.getInstance().gui.hud.getChat().addMessage(Component.translatable("disc_jockey.warning").withStyle(ChatFormatting.BOLD, ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError());
            warned = true;
            return;
        }
        if (running) stop();
        tick = 0;
        index = 0;
        this.song = song;
        // 确保歌曲的音符数据已加载
        try {
            SongLoader.ensureSongLoaded(song);
        } catch (IOException e) {
            Main.LOGGER.error("Failed to load song data for {}", song.fileName, e);
            // 不开始播放
            this.song = null;
            return;
        }
        if (song.notes.length == 0) {
            // 解析失败/空歌曲：播放线程会对 notes[0] 越界（老版本崩在 tickPlayback 的 “Index 0 out of bounds for length 0”）
            Main.LOGGER.error("Song {} has no playable notes, not starting playback", song.fileName);
            this.song = null;
            return;
        }
        //Main.LOGGER.info("Song length: " + song.length + " and tempo " + song.tempo);
        if (this.playbackThread == null) startPlaybackThread();
        running = true;
        rateLimiter.reset();
        tuner.reset();
        didSongReachEnd = false;
        if (playMode == PlayMode.RANDOM) {
            randomIndex = getRandomSongIndex(song.folder);
        }
    }

    public synchronized void stop() {
        stopPlaybackThread();
        running = false;
        index = 0;
        tick = 0;
        rateLimiter.reset();
        tuner.reset();
        didSongReachEnd = false; // Change after running stop() if actually ended cleanly
        song = null;
    }

    public synchronized void setPlayMode(PlayMode mode) {
        this.playMode = mode;
        this.loopSong = mode == PlayMode.SINGLE_LOOP;
    }

    public PlayMode getPlayMode() {
        return playMode;
    }

    private int getRandomSongIndex(SongLoader.SongFolder folder) {
        if (folder == null) {
            return SongLoader.SONGS.stream()
                    .filter(s -> s.folder == null)
                    .map(SongLoader.SONGS::indexOf)
                    .skip((long) (Math.random() * SongLoader.SONGS.stream().filter(s -> s.folder == null).count()))
                    .findFirst()
                    .orElse(0);
        } else {
            return (int) (Math.random() * folder.songs.size());
        }
    }

    public synchronized void playNextSong() {
        SongLoader.SongFolder folder = song != null ? song.folder : null;
        if (folder == null) {
            var mainSongs = SongLoader.SONGS.stream().filter(s -> s.folder == null).toList();
            if (mainSongs.isEmpty()) return;
            int currentIndex = mainSongs.indexOf(song);
            if (currentIndex == -1) return;
            int nextIndex = (currentIndex + 1) % mainSongs.size();
            start(mainSongs.get(nextIndex));
        } else {
            int currentIndex = folder.songs.indexOf(song);
            if (currentIndex == -1) return;
            int nextIndex = (currentIndex + 1) % folder.songs.size();
            start(folder.songs.get(nextIndex));
        }
    }

    public synchronized void playNextRandomSong() {
        SongLoader.SongFolder folder = song != null ? song.folder : null;
        randomIndex = getRandomSongIndex(folder);
        if (folder == null) {
            var mainSongs = SongLoader.SONGS.stream().filter(s -> s.folder == null).toList();
            if (!mainSongs.isEmpty()) {
                start(mainSongs.get(randomIndex));
            }
        } else {
            if (!folder.songs.isEmpty()) {
                start(folder.songs.get(randomIndex));
            }
        }
    }

    public synchronized void playPrevSong() {
        SongLoader.SongFolder folder = song != null ? song.folder : null;
        if (folder == null) {
            var mainSongs = SongLoader.SONGS.stream().filter(s -> s.folder == null).toList();
            if (mainSongs.isEmpty()) return;
            int currentIndex = mainSongs.indexOf(song);
            if (currentIndex == -1) return;
            int prevIndex = (currentIndex - 1 + mainSongs.size()) % mainSongs.size();
            start(mainSongs.get(prevIndex));
        } else {
            int currentIndex = folder.songs.indexOf(song);
            if (currentIndex == -1) return;
            int prevIndex = (currentIndex - 1 + folder.songs.size()) % folder.songs.size();
            start(folder.songs.get(prevIndex));
        }
    }

    /**
     * Can be run from both a separate thread or on minecraft ticks. Decided by Main.config.disableAsyncPlayback
     */
    public synchronized void tickPlayback() {
        if (!running) {
            lastPlaybackTickAt = Util.TIMESTAMP_UNINITIALIZED;
            rateLimiter.reset();
            return;
        }
        long previousPlaybackTickAt = lastPlaybackTickAt;
        lastPlaybackTickAt = Util.now();
        rateLimiter.tick();

        if(!tuner.isTuned()) return;

        while (running) {
            Minecraft client = Minecraft.getInstance();
            GameType gameMode = client.gameMode == null ? null : client.gameMode.getPlayerMode();
            // In the best case, gameMode would only be queried in sync Ticks, no here
            if (gameMode == null || !gameMode.isSurvival()) {
                client.executeIfPossible(() -> client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID+".player.invalid_game_mode", gameMode == null ? "unknown" : gameMode.getLongDisplayName()).withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError()));
                stop();
                return;
            }

            long note = song.notes[index];
            if ((short)note <= Math.round(tick)) {
                var instrumentMap = tuner.getNoteBlocks().get(Note.instrumentById((byte)(note >> Note.INSTRUMENT_SHIFT)));
                if (instrumentMap == null) {
                    // Instrument got likely mapped to "nothing". Skip it
                    index++;
                    continue;
                }
                BlockPos blockPos = instrumentMap.get((byte)(note >> Note.NOTE_SHIFT));
                if(blockPos == null) {
                    // Instrument got likely mapped to "nothing". Skip it
                    index++;
                    continue;
                }
                if (!Util.canInteractWith(client.player, blockPos)) {
                    if (!Main.config.autoSwitchNoteBlocks) {
                        stop();
                        client.executeIfPossible(() -> client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID+".player.too_far").withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError()));
                        return;
                    }
                    if (!tuner.rescanNoteBlocks(client)) {
                        stop();
                        client.executeIfPossible(() -> client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID+".player.too_far").withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError()));
                        return;
                    }
                    var newInstrumentMap = tuner.getNoteBlocks().get(Note.instrumentById((byte)(note >> Note.INSTRUMENT_SHIFT)));
                    if (newInstrumentMap == null) {
                        index++;
                        continue;
                    }
                    blockPos = newInstrumentMap.get((byte)(note >> Note.NOTE_SHIFT));
                    if (blockPos == null) {
                        index++;
                        continue;
                    }
                }
                Vec3 unit = Vec3.upFromBottomCenterOf(blockPos, 0.5).subtract(client.player.getEyePosition()).normalize();
                if (rateLimiter.canSendLookPacket()) {
                    Objects.requireNonNull(client.getConnection()).send(new ServerboundMovePlayerPacket.Rot(Mth.wrapDegrees((float) (Mth.atan2(unit.z, unit.x) * 57.2957763671875) - 90.0f), Mth.wrapDegrees((float) (-(Mth.atan2(unit.y, Math.sqrt(unit.x * unit.x + unit.z * unit.z)) * 57.2957763671875))), client.player.onGround(), client.player.horizontalCollision));                        rateLimiter.onLookPacketSent();
                    rateLimiter.onLookPacketSent();
                }
                if (rateLimiter.canSendAnyPacket()) {
                    // TODO: 5/30/2022 Check if the block needs tuning
                    client.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                    rateLimiter.onPacketSent();
                }
                if (rateLimiter.canSendCosmeticPacket()) {
                    client.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, blockPos, Direction.UP, 0));
                    rateLimiter.onPacketSent();
                }
                if (rateLimiter.canSendSwingPacket()) {
                    client.executeIfPossible(() -> {
                        if (client.player != null) {
                            client.player.swing(InteractionHand.MAIN_HAND);
                        }
                    });
                    rateLimiter.onSwingPacketSent();
                }

                index++;
                if (index >= song.notes.length) {
                    Song currentSong = song;
                    didSongReachEnd = true;
                    if (playMode == PlayMode.SINGLE_LOOP) {
                        start(currentSong);
                    } else if (playMode == PlayMode.LIST_LOOP) {
                        playNextSong();
                    } else if (playMode == PlayMode.RANDOM) {
                        playNextRandomSong();
                    } else {
                        stop();
                    }
                    break;
                }
            } else {
                break;
            }
        }

        if (running) { // Might not be running anymore (prevent small offset on song, even if that is not played anymore)
            long elapsedMs = previousPlaybackTickAt != -1L && lastPlaybackTickAt != -1L ? lastPlaybackTickAt - previousPlaybackTickAt : 16; // Assume 16ms if unknown
            tick += song.millisecondsToTicks(elapsedMs) * speed;
        }
    }

    @Override
    public void onStartTick(@Nullable ClientLevel world) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        if (song == null || !running) return;

        tuner.cleanup(); // Housekeeping

        // Select song
        if (!tuner.isSongSelected()) {
            if (!tuner.selectSong(client, song)) {
                if (!tuner.getMissingInstrumentBlocks().isEmpty()) {
                    ChatComponent chatHud = Minecraft.getInstance().gui.hud.getChat();
                    chatHud.addMessage(Component.translatable(Main.MOD_ID + ".player.invalid_note_blocks").withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError());
                    tuner.getMissingInstrumentBlocks().forEach((block, integer) -> chatHud.addMessage(Component.literal(block.getName().getString() + " × " + integer).withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError()));
                    stop();
                    return;
                } else {
                    Main.LOGGER.error("Failed to select song to unknown / unexpected reason!");
                    client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID + ".selectsong_fail_unknown").withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError());
                    stop();
                    return;
                }
            } else {
                Main.LOGGER.info("Selected song: " + song.displayName + " (" + song.fileName + ")");
            }
        }

        // Tune
        if (!tuner.isTuned()) {
            Tuner.TuningFail tuningFail = tuner.tickTuning(client);
            if (tuningFail == Tuner.TuningFail.MovedTooFarAway) {
                stop();
                client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID + ".player.too_far").withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError());
                return;
            } else if (tuningFail != null) {
                stop();
                Main.LOGGER.error("Tuning song failed: " + tuningFail.name());
                client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID + ".player.tuning_fail_other", tuningFail.name()).withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError());
                return;
            }
        }

        if (tuner.isTuned() && (playbackThread == null || !playbackThread.isAlive()) && running && Main.config.disableAsyncPlayback) {
            // Sync playback (off by default). Replacement for playback thread
            try {
                tickPlayback();
            } catch (Exception ex) {
                Main.LOGGER.error("Failed to tick playback synchronously!", ex);
                stop();
            }
        }
    }

    public void setSongElapsedSeconds(double seconds) {
        tick = song.millisecondsToTicks((long) seconds * 1000);
        index = 0;
        for (int i = 0; i < song.notes.length; i++) {
            long note = song.notes[i];
            if ((short) note >= Math.round(tick)) {
                index = i;
                //Main.LOGGER.info("Seconds: " + seconds + ", Tick: " + tick + ", Index: " + index);
                break;
            }
        }
    }

    public double getSongElapsedSeconds() {
        if (song == null) return 0;
        return song.ticksToMilliseconds(tick) / 1000;
    }

    public float getProgress() {
        if (song == null) return 0;
        return (float) (tick / song.length);
    }

    public String getFormattedTime() {
        if (song == null) return "00:00 / 00:00";
        double elapsedSeconds = song.ticksToMilliseconds(tick) / 1000;
        double totalSeconds = song.getLengthInSeconds();
        return formatTime(elapsedSeconds) + " / " + formatTime(totalSeconds);
    }

    private String formatTime(double seconds) {
        int minutes = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        return String.format("%02d:%02d", minutes, secs);
    }
}
