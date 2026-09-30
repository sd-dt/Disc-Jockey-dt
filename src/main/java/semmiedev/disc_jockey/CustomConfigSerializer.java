package semmiedev.disc_jockey;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.serializer.ConfigSerializer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CustomConfigSerializer implements ConfigSerializer<semmiedev.disc_jockey.Config> {
    private final Gson gson;

    public CustomConfigSerializer(Config annotation, Class<semmiedev.disc_jockey.Config> configClass) {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    @Override
    public void serialize(semmiedev.disc_jockey.Config config) throws SerializationException {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve(Main.MOD_ID);
            Files.createDirectories(configDir);
            
            Path configFile = configDir.resolve("config.json");
            String json = gson.toJson(config);
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public semmiedev.disc_jockey.Config deserialize() throws SerializationException {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve(Main.MOD_ID);
            Path configFile = configDir.resolve("config.json");
            
            if (!Files.exists(configFile)) {
                semmiedev.disc_jockey.Config config = createDefault();
                serialize(config);
                return config;
            }
            
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            return gson.fromJson(json, semmiedev.disc_jockey.Config.class);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public semmiedev.disc_jockey.Config createDefault() {
        try {
            return semmiedev.disc_jockey.Config.class.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}