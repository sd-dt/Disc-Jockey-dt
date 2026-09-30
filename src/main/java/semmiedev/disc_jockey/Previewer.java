package semmiedev.disc_jockey;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

public class Previewer implements ClientTickEvents.StartLevelTick {
    public boolean running;

    private int i;
    private float tick;
    private Song song;

    public enum PlayMode {
        SINGLE_LOOP,
        LIST_LOOP,
        RANDOM,
        STOP_AFTER
    }

    private PlayMode playMode = PlayMode.STOP_AFTER;

    public void start(Song song) {
        // 确保歌曲的音符数据已加载
        try {
            SongLoader.ensureSongLoaded(song);
        } catch (IOException e) {
            Main.LOGGER.error("Failed to load song data for preview: {}", song.fileName, e);
            return;
        }
        if (song.notes.length == 0) {
            // 空歌曲（解析失败或真的没有音符）：试听循环会对 notes[0] 越界，直接不开始
            Main.LOGGER.error("Song {} has no playable notes, not starting preview", song.fileName);
            return;
        }
        this.song = song;
        i = 0;
        tick = 0;
        if (!Main.TICK_LISTENERS.contains(this)) {
            Main.TICK_LISTENERS.add(this);
        }
        running = true;
    }

    public void stop() {
        Minecraft.getInstance().schedule(() -> Main.TICK_LISTENERS.remove(this));
        running = false;
        i = 0;
        tick = 0;
        song = null;
    }

    public Song getSong() {
        return song;
    }

    public double getSongElapsedSeconds() {
        if (song == null) return 0;
        return song.ticksToMilliseconds(tick) / 1000;
    }

    public void setSongElapsedSeconds(double seconds) {
        if (song == null) return;
        tick = (float) song.millisecondsToTicks((long) seconds * 1000);
        i = 0;
        for (int idx = 0; idx < song.notes.length; idx++) {
            long note = song.notes[idx];
            if ((short) note >= Math.round(tick)) {
                i = idx;
                break;
            }
        }
    }

    public PlayMode getPlayMode() {
        return playMode;
    }

    public void setPlayMode(PlayMode mode) {
        this.playMode = mode;
    }

    @Override
    public void onStartTick(@Nullable ClientLevel world) {
        if (!running || song == null) return;

        while (true) {
            long note = song.notes[i];
            if ((short)note == Math.round(tick)) {
                Vec3 pos = Minecraft.getInstance().gameRenderer.mainCamera().position();
                if (world != null) {
                    world.playLocalSound(pos.x, pos.y, pos.z, Note.instrumentById((byte)(note >> Note.INSTRUMENT_SHIFT)).getSoundEvent().value(), SoundSource.RECORDS, 3, (float)Math.pow(2.0, ((byte)(note >> Note.NOTE_SHIFT) - 12) / 12.0), false);
                }
                i++;
                if (i >= song.notes.length) {
                    if (playMode == PlayMode.SINGLE_LOOP) {
                        start(song);
                    } else if (playMode == PlayMode.LIST_LOOP) {
                        playNextSong();
                    } else if (playMode == PlayMode.RANDOM) {
                        playNextRandomSong();
                    } else {
                        stop();
                    }
                    return;
                }
            } else {
                break;
            }
        }

        tick += song.tempo / 100f / 20f;
    }

    public void playNextSong() {
        if (song == null || song.folder == null) {
            var mainSongs = SongLoader.SONGS.stream().filter(s -> s.folder == null).toList();
            if (mainSongs.isEmpty()) {
                stop();
                return;
            }
            int currentIndex = mainSongs.indexOf(song);
            if (currentIndex == -1) {
                stop();
                return;
            }
            int nextIndex = (currentIndex + 1) % mainSongs.size();
            start(mainSongs.get(nextIndex));
        } else {
            if (song.folder.songs.isEmpty()) {
                stop();
                return;
            }
            int currentIndex = song.folder.songs.indexOf(song);
            if (currentIndex == -1) {
                stop();
                return;
            }
            int nextIndex = (currentIndex + 1) % song.folder.songs.size();
            start(song.folder.songs.get(nextIndex));
        }
    }

    public void playNextRandomSong() {
        playRandomSong();
    }

    public void playPrevSong() {
        if (song == null || song.folder == null) {
            var mainSongs = SongLoader.SONGS.stream().filter(s -> s.folder == null).toList();
            if (mainSongs.size() <= 1) {
                stop();
                return;
            }
            int currentIndex = mainSongs.indexOf(song);
            if (currentIndex == -1) {
                stop();
                return;
            }
            int prevIndex = (currentIndex - 1 + mainSongs.size()) % mainSongs.size();
            start(mainSongs.get(prevIndex));
        } else {
            if (song.folder.songs.size() <= 1) {
                stop();
                return;
            }
            int currentIndex = song.folder.songs.indexOf(song);
            if (currentIndex == -1) {
                stop();
                return;
            }
            int prevIndex = (currentIndex - 1 + song.folder.songs.size()) % song.folder.songs.size();
            start(song.folder.songs.get(prevIndex));
        }
    }

    private void playRandomSong() {
        if (song == null || song.folder == null) {
            var mainSongs = SongLoader.SONGS.stream().filter(s -> s.folder == null).toList();
            if (mainSongs.isEmpty()) {
                stop();
                return;
            }
            int randomIndex = (int) (Math.random() * mainSongs.size());
            start(mainSongs.get(randomIndex));
        } else {
            if (song.folder.songs.isEmpty()) {
                stop();
                return;
            }
            int randomIndex = (int) (Math.random() * song.folder.songs.size());
            start(song.folder.songs.get(randomIndex));
        }
    }

    public float getProgress() {
        if (song == null) return 0;
        return tick / song.length;
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
