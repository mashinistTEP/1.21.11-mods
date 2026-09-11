package com.godequipment;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GodEquipmentMod implements ModInitializer {

    public static final String MOD_ID = "godequipment";
    public static final Logger LOGGER = LoggerFactory.getLogger("GOD EQUIPMENT");

    @Override
    public void onInitialize() {
        EnchantData.load();
        GodEquipmentConfig.load();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CustomGiveCommand.register(dispatcher, registryAccess);
            EnchCommand.register(dispatcher);
        });

        LOGGER.info("[GOD EQUIPMENT] Загружено: {} зачарований, {} названий предметов.",
                EnchantData.enchantCount(), EnchantData.itemCount());
        LOGGER.info("[GOD EQUIPMENT] config/godequipment.json -> unlimitedEnchantLevel={}, anyEnchantCombo={}, anyItemAnyEnchant={}",
                GodEquipmentConfig.isUnlimitedEnchantLevel(),
                GodEquipmentConfig.isAnyEnchantCombo(),
                GodEquipmentConfig.isAnyItemAnyEnchant());
    }
}
