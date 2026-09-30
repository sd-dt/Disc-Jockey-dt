package semmiedev.disc_jockey;

import com.mojang.blaze3d.platform.InputConstants;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import semmiedev.disc_jockey.gui.hud.PlaybackProgressOverlay;
import semmiedev.disc_jockey.gui.screen.DiscJockeyScreen;

import java.io.File;
import java.util.ArrayList;

public class Main implements ClientModInitializer {
    public static final String MOD_ID = "disc_jockey";
    public static final MutableComponent NAME = Component.literal("Disc Jockey");
    public static final Logger LOGGER = LogManager.getLogger("Disc Jockey");
    public static final ArrayList<ClientTickEvents.StartLevelTick> TICK_LISTENERS = new ArrayList<>();
    public static final Previewer PREVIEWER = new Previewer();
    public static final SongPlayer SONG_PLAYER = new SongPlayer();

    public static File songsFolder;
    public static Config config;
    public static ConfigHolder<Config> configHolder;

    @Override
    public void onInitializeClient() {
        configHolder = AutoConfig.register(Config.class, CustomConfigSerializer::new);
        config = configHolder.getConfig();

        ClientLifecycleEvents.CLIENT_STOPPING.register(_ -> {
            if (!config.rememberLastSelectedOnRestart && !config.lastSelectedSong.isEmpty()) {
                config.lastSelectedSong = "";
                configHolder.save();
            }
        });

        

        songsFolder = new File(FabricLoader.getInstance().getConfigDir()+File.separator+MOD_ID+File.separator+"songs");
        if (!songsFolder.isDirectory() && !songsFolder.mkdirs()) {
            LOGGER.warn("Failed to create songs folder: {}", songsFolder.getAbsolutePath());
        }

        SongLoader.loadSongs();

        //KeyBinding openScreenKeyBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(MOD_ID+".key_bind.open_screen", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.category."+MOD_ID));
        // 修复按键绑定
        KeyMapping openScreenKeyBind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                MOD_ID + ".key_bind.open_screen",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.START_CLIENT_TICK.register(new ClientTickEvents.StartTick() {
            private ClientLevel prevWorld;

            @Override
            public void onStartTick(@Nullable Minecraft client) {
                if (client == null) return;
                if (prevWorld != client.level) {
                    PREVIEWER.stop();
                    SONG_PLAYER.stop();
                }
                prevWorld = client.level;

                // 每 tick 按方块交互距离刷新「预期服务器版本」（只在值变化时写字段 / 打日志）
                updateExpectedServerVersion(client);

                if (openScreenKeyBind.consumeClick()) {
                    if (SongLoader.loadingSongs) {
//                        client.gui.getChat().addMessage(Component.translatable(Main.MOD_ID+".still_loading").withStyle(ChatFormatting.RED));
                        client.gui.hud.getChat().addMessage(Component.translatable(Main.MOD_ID+".still_loading").withStyle(ChatFormatting.RED), null, GuiMessageSource.PLAYER, GuiMessageTag.chatError());
                        SongLoader.showToast = true;
                    } else {
                        client.gui.setScreen(new DiscJockeyScreen());
                    }
                }
            }
        });

        ClientTickEvents.START_LEVEL_TICK.register(world -> {
            for (ClientTickEvents.StartLevelTick listener : new java.util.ArrayList<>(TICK_LISTENERS)) listener.onStartTick(world);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> DiscjockeyCommand.register(dispatcher));

        ClientLoginConnectionEvents.DISCONNECT.register((_, _) -> {
            PREVIEWER.stop();
            SONG_PLAYER.stop();
        });

        HudElementRegistry.addLast(Identifier.withDefaultNamespace(Main.MOD_ID + "/" + "playback_progress"), new PlaybackProgressOverlay());
    }

    /**
     * 自动判断扫描音符盒用哪一档范围（取代 26.2 基线的 ExpectedServerVersion 用户设置项）。
     *
     * Tuner 的两档行为（见 Tuner#selectSong / #rescanNoteBlocks）：
     *   v1_20_5_Or_Later      -> maxOffset = ceil(交互距离 + 2)
     *   v1_20_4_Or_Earlier    -> maxOffset = 7
     *   All（通用档）          -> maxOffset = min(7, ceil(交互距离 + 2))
     *
     * 只有 1.20.5+ 的服务端才可能把 block_interaction_range 调大（≤1.20.4 根本同步不了这个属性，
     * 客户端只会是默认值 4.5/5.0，此时 ceil(range + 2) 正好是 7），所以：
     *   range > 5 -> 走不封顶的 1.20.5+ 档；否则走通用档（结果与固定 7 等价，且不会超出实际可交互范围）。
     * 这样既不依赖用户配置，也不受 ViaVersion 之类代理伪报服务器版本的影响，也不用 ping / 协议号。
     */
    private static void updateExpectedServerVersion(Minecraft client) {
        if (client.player == null) return;

        double range = client.player.blockInteractionRange();
        Config.ExpectedServerVersion detected = range > 5.0
                ? Config.ExpectedServerVersion.v1_20_5_Or_Later
                : Config.ExpectedServerVersion.All;

        if (config.expectedServerVersion != detected) {
            config.expectedServerVersion = detected;
            LOGGER.info("Auto-detected expected server version: {} (block interaction range: {})", detected, range);
        }
    }
}
