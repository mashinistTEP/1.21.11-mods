package com.godequipment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки мода: {@code config/godequipment.json} (создаётся автоматически
 * при первом запуске со значениями по умолчанию — всё выключено, то есть
 * ведёт себя максимально "по-ванильному": уровни зачарований обрезаются
 * до обычного максимума, несовместимые чары и "неправильные" предметы
 * отфильтровываются).
 * <p>
 * Раньше это были три отдельных мода-аддона — теперь это просто три флага
 * в одном файле. Чтобы что-то изменить: отредактируйте JSON и перезапустите
 * игру/сервер (файл читается один раз при старте).
 */
public final class GodEquipmentConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Data data = new Data();

    /** Простой POJO для Gson — поля должны остаться public, чтобы сериализация работала как есть. */
    public static class Data {
        public boolean unlimitedEnchantLevel = false;
        public boolean anyEnchantCombo = false;
        public boolean anyItemAnyEnchant = false;
    }

    private GodEquipmentConfig() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("godequipment.json");
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    Data loaded = GSON.fromJson(reader, Data.class);
                    if (loaded != null) {
                        data = loaded;
                    }
                }
            } else {
                save(path);
            }
        } catch (IOException e) {
            GodEquipmentMod.LOGGER.error(
                    "[GOD EQUIPMENT] Не удалось прочитать/создать config/godequipment.json, использую значения по умолчанию: {}",
                    e.toString());
        }
    }

    private static void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        }
    }

    public static boolean isUnlimitedEnchantLevel() {
        return data.unlimitedEnchantLevel;
    }

    public static boolean isAnyEnchantCombo() {
        return data.anyEnchantCombo;
    }

    public static boolean isAnyItemAnyEnchant() {
        return data.anyItemAnyEnchant;
    }
}
