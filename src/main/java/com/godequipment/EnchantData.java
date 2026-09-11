package com.godequipment;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Загружает и хранит данные мода из JSON-файлов (лежат в ресурсах, рядом
 * с этим классом — их можно редактировать без пересборки исходников,
 * только пересобрав/переложив ресурсы).
 * <p>
 * Все данные грузятся один раз при старте ({@link #load()}), поэтому
 * доступ к ним из команд не требует повторного чтения файлов.
 */
public final class EnchantData {

    private static final Gson GSON = new Gson();
    private static final String BASE = "/godequipment/data/";

    private static Map<String, String> itemNamesRu = Map.of();
    private static Map<String, String> enchantNamesRu = Map.of();
    private static Map<String, Integer> enchantMaxLevels = Map.of();
    private static List<Set<String>> exclusiveGroups = List.of();
    private static Map<String, Set<String>> applicableKeywords = Map.of();

    private EnchantData() {
    }

    public static void load() {
        itemNamesRu = loadStringMap("item_names_ru.json");
        enchantNamesRu = loadStringMap("enchant_names_ru.json");
        enchantMaxLevels = loadIntMap("enchant_max_levels.json");
        exclusiveGroups = loadGroups("enchant_exclusive_groups.json");
        applicableKeywords = loadStringListMap("enchant_applicable_items.json");
    }

    // ------------------------------------------------------------------
    // Публичное API, которым пользуются команды
    // ------------------------------------------------------------------

    /** Русское/английское название предмета -> голый id предмета (без namespace). */
    public static String resolveItemId(String token) {
        String key = normalizeKey(token);
        String mapped = itemNamesRu.get(key);
        if (mapped != null) return mapped;
        return stripNamespace(key);
    }

    /** Русское/английское название зачарования -> голый id зачарования (без namespace). */
    public static String resolveEnchantId(String token) {
        String key = normalizeKey(token);
        String mapped = enchantNamesRu.get(key);
        if (mapped != null) return mapped;
        return stripNamespace(key);
    }

    /** Ванильный максимальный уровень зачарования, либо null, если зачарование неизвестно данным мода. */
    public static Integer getVanillaMaxLevel(String enchantId) {
        return enchantMaxLevels.get(enchantId);
    }

    /** Группы взаимоисключающих зачарований (по id, без namespace). */
    public static List<Set<String>> getExclusiveGroups() {
        return exclusiveGroups;
    }

    /**
     * Проверяет, "подходит" ли предмет зачарованию по обычной логике игры
     * (грубая проверка по подстроке id предмета). Если для зачарования нет
     * записи в enchant_applicable_items.json — считаем, что ограничений нет
     * (например, для неизвестных/модовых зачарований).
     */
    public static boolean isNormallyApplicable(String enchantId, String itemId) {
        Set<String> keywords = applicableKeywords.get(enchantId);
        if (keywords == null || keywords.isEmpty()) return true;
        for (String keyword : keywords) {
            if (itemId.contains(keyword)) return true;
        }
        return false;
    }

    public static int enchantCount() {
        return enchantNamesRu.size();
    }

    public static int itemCount() {
        return itemNamesRu.size();
    }

    // ------------------------------------------------------------------
    // Загрузка и нормализация
    // ------------------------------------------------------------------

    /** Приводит пользовательский ввод к единому виду: нижний регистр, пробелы -> "_" . */
    public static String normalizeKey(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String stripNamespace(String key) {
        int idx = key.indexOf(':');
        return idx >= 0 ? key.substring(idx + 1) : key;
    }

    private static Map<String, String> loadStringMap(String fileName) {
        try (InputStream in = EnchantData.class.getResourceAsStream(BASE + fileName)) {
            if (in == null) {
                GodEquipmentMod.LOGGER.warn("[GOD EQUIPMENT] Не найден файл данных: {}", fileName);
                return new HashMap<>();
            }
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> raw = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
            Map<String, String> normalized = new HashMap<>();
            if (raw != null) {
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    normalized.put(normalizeKey(e.getKey()), e.getValue());
                }
            }
            return normalized;
        } catch (Exception e) {
            GodEquipmentMod.LOGGER.error("[GOD EQUIPMENT] Ошибка чтения {}: {}", fileName, e.toString());
            return new HashMap<>();
        }
    }

    private static Map<String, Integer> loadIntMap(String fileName) {
        try (InputStream in = EnchantData.class.getResourceAsStream(BASE + fileName)) {
            if (in == null) return new HashMap<>();
            Type type = new TypeToken<Map<String, Double>>() {}.getType();
            Map<String, Double> raw = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
            Map<String, Integer> result = new HashMap<>();
            if (raw != null) {
                for (Map.Entry<String, Double> e : raw.entrySet()) {
                    result.put(e.getKey(), e.getValue().intValue());
                }
            }
            return result;
        } catch (Exception e) {
            GodEquipmentMod.LOGGER.error("[GOD EQUIPMENT] Ошибка чтения {}: {}", fileName, e.toString());
            return new HashMap<>();
        }
    }

    private static List<Set<String>> loadGroups(String fileName) {
        try (InputStream in = EnchantData.class.getResourceAsStream(BASE + fileName)) {
            if (in == null) return new ArrayList<>();
            Type type = new TypeToken<List<List<String>>>() {}.getType();
            List<List<String>> raw = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
            List<Set<String>> result = new ArrayList<>();
            if (raw != null) {
                for (List<String> group : raw) {
                    result.add(new HashSet<>(group));
                }
            }
            return result;
        } catch (Exception e) {
            GodEquipmentMod.LOGGER.error("[GOD EQUIPMENT] Ошибка чтения {}: {}", fileName, e.toString());
            return new ArrayList<>();
        }
    }

    private static Map<String, Set<String>> loadStringListMap(String fileName) {
        try (InputStream in = EnchantData.class.getResourceAsStream(BASE + fileName)) {
            if (in == null) return new HashMap<>();
            Type type = new TypeToken<Map<String, List<String>>>() {}.getType();
            Map<String, List<String>> raw = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
            Map<String, Set<String>> result = new HashMap<>();
            if (raw != null) {
                for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                    result.put(e.getKey(), new HashSet<>(e.getValue()));
                }
            }
            return result;
        } catch (Exception e) {
            GodEquipmentMod.LOGGER.error("[GOD EQUIPMENT] Ошибка чтения {}: {}", fileName, e.toString());
            return new HashMap<>();
        }
    }
}
