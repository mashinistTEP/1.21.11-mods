package com.godequipment.mixin;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.GiveCommand;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GOD EQUIPMENT полностью переопределяет /give.
 * <p>
 * Ванильный {@link GiveCommand#register(CommandDispatcher, CommandRegistryAccess)}
 * отменяется в самом начале (HEAD), чтобы он вообще не добавлял свой узел "give"
 * в дерево команд Brigadier. Взамен {@link com.godequipment.GodEquipmentMod}
 * регистрирует {@link com.godequipment.CustomGiveCommand} — команду с тем же
 * именем, но собственным синтаксисом зачарований.
 */
@Mixin(GiveCommand.class)
public class GiveCommandMixin {

    @Inject(method = "register", at = @At("HEAD"), cancellable = true)
    private static void godequipment$cancelVanillaGive(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CallbackInfo ci
    ) {
        ci.cancel();
    }
}
