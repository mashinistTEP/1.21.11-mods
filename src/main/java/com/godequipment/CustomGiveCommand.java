package com.godequipment;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgument;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Замена ванильной /give.
 * <p>
 * Синтаксис: {@code /give <цели> <предмет>[{ench: имя_уровень, имя_уровень, ...}] [количество]}
 * <p>
 * Пример: {@code /give @s netherite_sword{ench: sharpness_5, looting_3} 1}<br>
 * Пример на русском: {@code /give @s Незеритовый_меч{ench: добыча_5, острота_10}}
 * <p>
 * Три доп. поведения (снятие лимита уровня, разрешение любых сочетаний
 * зачарований, разрешение любых зачарований на любых предметах) включаются
 * через {@code config/godequipment.json} — см. {@link GodEquipmentConfig}.
 */
public final class CustomGiveCommand {

    private static final Pattern SPEC_PATTERN = Pattern.compile(
            "^(?<item>[^\\s{]+)(\\{\\s*ench:\\s*(?<ench>[^}]*)\\})?\\s*(?<count>\\d+)?\\s*$"
    );

    private static final Pattern ENCH_TOKEN_PATTERN = Pattern.compile("^(?<name>.+)_(?<lvl>\\d+)$");

    private static final int HARD_LEVEL_CAP = 32767;

    private CustomGiveCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(CommandManager.literal("give")
                .requires(source -> source.getPermissions().hasPermission(LeveledPermissionPredicate.GAMEMASTERS))
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.argument("spec", StringArgumentType.greedyString())
                                .executes(ctx -> execute(ctx, registryAccess)))));
    }

    private static int execute(CommandContext<ServerCommandSource> ctx, CommandRegistryAccess registryAccess) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
        String spec = StringArgumentType.getString(ctx, "spec").trim();

        Matcher specMatcher = SPEC_PATTERN.matcher(spec);
        if (!specMatcher.matches()) {
            throw new SimpleCommandExceptionType(Text.literal(
                    "GOD EQUIPMENT: не удалось разобрать запись предмета: \"" + spec + "\". " +
                            "Формат: <предмет>{ench: имя_уровень, ...} <количество>")).create();
        }

        String itemToken = specMatcher.group("item");
        String enchBlock = specMatcher.group("ench");
        String countText = specMatcher.group("count");

        String itemId = EnchantData.resolveItemId(itemToken);
        int count = countText != null ? Math.max(1, Integer.parseInt(countText)) : 1;

        boolean unlimitedLevels = GodEquipmentConfig.isUnlimitedEnchantLevel();
        boolean anyCombo = GodEquipmentConfig.isAnyEnchantCombo();
        boolean anyItem = GodEquipmentConfig.isAnyItemAnyEnchant();

        LinkedHashMap<String, Integer> enchants = parseEnchantBlock(enchBlock);
        enchants = clampLevels(enchants, unlimitedLevels);
        if (!anyCombo) {
            enchants = filterExclusive(enchants);
        }
        if (!anyItem) {
            enchants = filterApplicability(enchants, itemId, source);
        }

        Registry<Enchantment> enchantRegistry = source.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        LinkedHashMap<String, Integer> validEnchants = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            RegistryKey<Enchantment> key = RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of("minecraft", e.getKey()));
            Optional<RegistryEntry.Reference<Enchantment>> entry = enchantRegistry.getEntry(key.getValue());
            if (entry.isEmpty()) {
                source.sendError(Text.literal("GOD EQUIPMENT: неизвестное зачарование \"" + e.getKey() + "\" — пропущено"));
                continue;
            }
            validEnchants.put(e.getKey(), e.getValue());
        }

        String builtSpec = buildVanillaItemString(itemId, validEnchants);

        ItemStackArgumentType itemStackArgumentType = ItemStackArgumentType.itemStack(registryAccess);
        ItemStackArgument parsedItem;
        try {
            parsedItem = itemStackArgumentType.parse(new StringReader(builtSpec));
        } catch (CommandSyntaxException e) {
            throw new SimpleCommandExceptionType(Text.literal(
                    "GOD EQUIPMENT: неизвестный предмет \"" + itemToken + "\" (id: " + itemId + ")")).create();
        }

        int affected = 0;
        for (ServerPlayerEntity player : targets) {
            ItemStack stack = parsedItem.createStack(count, false);
            boolean fullyGiven = player.giveItemStack(stack);
            if (!fullyGiven && !stack.isEmpty()) {
                player.dropItem(stack, false);
            }
            affected++;
        }

        String feedbackItem = itemId;
        int feedbackCount = count;
        int feedbackAffected = affected;
        source.sendFeedback(() -> Text.literal(
                "GOD EQUIPMENT: выдано " + feedbackItem + " x" + feedbackCount +
                        " (игроков: " + feedbackAffected + ")"), true);

        return affected;
    }

    private static LinkedHashMap<String, Integer> parseEnchantBlock(String enchBlock) throws CommandSyntaxException {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        if (enchBlock == null || enchBlock.isBlank()) return result;

        for (String rawToken : enchBlock.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) continue;

            Matcher m = ENCH_TOKEN_PATTERN.matcher(token);
            if (!m.matches()) {
                throw new SimpleCommandExceptionType(Text.literal(
                        "GOD EQUIPMENT: не удалось разобрать зачарование \"" + token + "\" " +
                                "(ожидался формат имя_уровень, например острота_5)")).create();
            }

            String enchId = EnchantData.resolveEnchantId(m.group("name"));
            int level;
            try {
                level = Integer.parseInt(m.group("lvl"));
            } catch (NumberFormatException ex) {
                continue;
            }
            result.put(enchId, level);
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> clampLevels(LinkedHashMap<String, Integer> input, boolean unlimited) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : input.entrySet()) {
            int level = e.getValue();
            if (!unlimited) {
                Integer vanillaMax = EnchantData.getVanillaMaxLevel(e.getKey());
                if (vanillaMax != null && level > vanillaMax) {
                    level = vanillaMax;
                }
            }
            level = Math.max(1, Math.min(level, HARD_LEVEL_CAP));
            result.put(e.getKey(), level);
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> filterExclusive(LinkedHashMap<String, Integer> input) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        Set<String> blocked = new HashSet<>();
        for (Map.Entry<String, Integer> e : input.entrySet()) {
            String id = e.getKey();
            if (blocked.contains(id)) continue;
            result.put(id, e.getValue());
            for (Set<String> group : EnchantData.getExclusiveGroups()) {
                if (group.contains(id)) {
                    blocked.addAll(group);
                    blocked.remove(id);
                }
            }
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> filterApplicability(LinkedHashMap<String, Integer> input, String itemId, ServerCommandSource source) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : input.entrySet()) {
            if (EnchantData.isNormallyApplicable(e.getKey(), itemId)) {
                result.put(e.getKey(), e.getValue());
            } else {
                source.sendError(Text.literal(
                        "GOD EQUIPMENT: \"" + e.getKey() + "\" обычно нельзя наложить на " + itemId +
                                " — пропущено (включите anyItemAnyEnchant в config/godequipment.json)"));
            }
        }
        return result;
    }

    static String buildVanillaItemString(String itemId, LinkedHashMap<String, Integer> enchants) {
        StringBuilder sb = new StringBuilder("minecraft:").append(itemId);
        if (!enchants.isEmpty()) {
            sb.append("[enchantments={");
            boolean first = true;
            for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append("minecraft:").append(e.getKey()).append("\":").append(e.getValue());
            }
            sb.append("}]");
        }
        return sb.toString();
    }
}
