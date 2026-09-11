package com.godequipment;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
 * {@code /ench <цели> <имя_уровень, имя_уровень, ...>}
 * <p>
 * Накладывает перечисленные зачарования на предмет в основной руке каждой
 * цели (существующие зачарования не стираются — только дополняются/
 * перезаписываются по имени). Названия — на русском или английском, как
 * и в /give.
 * <p>
 * Пример: {@code /ench @s острота_10, добыча_5}
 */
final class EnchCommand {

    private static final Pattern ENCH_TOKEN_PATTERN = Pattern.compile("^(?<name>.+)_(?<lvl>\\d+)$");
    private static final int HARD_LEVEL_CAP = 32767;

    private EnchCommand() {
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("ench")
                .requires(source -> source.getPermissions().hasPermission(LeveledPermissionPredicate.GAMEMASTERS))
                .then(CommandManager.argument("targets", EntityArgumentType.players())
                        .then(CommandManager.argument("enchantments", StringArgumentType.greedyString())
                                .executes(EnchCommand::execute))));
    }

    private static int execute(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
        String raw = StringArgumentType.getString(ctx, "enchantments");

        boolean unlimitedLevels = GodEquipmentConfig.isUnlimitedEnchantLevel();
        boolean anyCombo = GodEquipmentConfig.isAnyEnchantCombo();
        boolean anyItem = GodEquipmentConfig.isAnyItemAnyEnchant();

        LinkedHashMap<String, Integer> requested = parseTokens(raw);
        requested = clampLevels(requested, unlimitedLevels);
        if (!anyCombo) {
            requested = filterExclusive(requested);
        }

        Registry<Enchantment> enchantRegistry = source.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);

        int affected = 0;
        for (ServerPlayerEntity player : targets) {
            ItemStack stack = player.getMainHandStack();
            if (stack.isEmpty()) {
                source.sendError(Text.literal("GOD EQUIPMENT: у " + player.getStringifiedName() + " пустая рука — пропущено"));
                continue;
            }

            LinkedHashMap<String, Integer> toApply = requested;
            if (!anyItem) {
                toApply = filterApplicability(requested, stack, source);
            }

            int applied = 0;
            for (Map.Entry<String, Integer> e : toApply.entrySet()) {
                RegistryKey<Enchantment> key = RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of("minecraft", e.getKey()));
                Optional<RegistryEntry.Reference<Enchantment>> entry = enchantRegistry.getEntry(key.getValue());
                if (entry.isEmpty()) {
                    source.sendError(Text.literal("GOD EQUIPMENT: неизвестное зачарование \"" + e.getKey() + "\" — пропущено"));
                    continue;
                }
                EnchantmentHelper.apply(stack, builder -> builder.set(entry.get(), e.getValue()));
                applied++;
            }

            if (applied > 0) {
                affected++;
            }
        }

        int finalAffected = affected;
        source.sendFeedback(() -> Text.literal("GOD EQUIPMENT: /ench применена к " + finalAffected + " игрокам"), true);
        return affected;
    }

    private static LinkedHashMap<String, Integer> parseTokens(String raw) throws CommandSyntaxException {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String rawToken : raw.split(",")) {
            String token = rawToken.trim();
            if (token.isEmpty()) continue;

            Matcher m = ENCH_TOKEN_PATTERN.matcher(token);
            if (!m.matches()) {
                throw new SimpleCommandExceptionType(Text.literal(
                        "GOD EQUIPMENT: не удалось разобрать \"" + token + "\" " +
                                "(ожидался формат имя_уровень, например острота_5)")).create();
            }

            String enchId = EnchantData.resolveEnchantId(m.group("name"));
            try {
                result.put(enchId, Integer.parseInt(m.group("lvl")));
            } catch (NumberFormatException ignored) {
                // некорректное число — токен пропускается
            }
        }
        return result;
    }

    private static LinkedHashMap<String, Integer> clampLevels(LinkedHashMap<String, Integer> input, boolean unlimited) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : input.entrySet()) {
            int level = e.getValue();
            if (!unlimited) {
                Integer max = EnchantData.getVanillaMaxLevel(e.getKey());
                if (max != null && level > max) level = max;
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

    private static LinkedHashMap<String, Integer> filterApplicability(LinkedHashMap<String, Integer> input, ItemStack stack, ServerCommandSource source) {
        String itemId = Registries.ITEM.getId(stack.getItem()).getPath();
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
}
