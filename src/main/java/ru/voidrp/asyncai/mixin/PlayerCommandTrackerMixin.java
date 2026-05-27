package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.CurrentCommandPlayer;

/**
 * Tracks which player is currently executing a chat command so that
 * CraftWorldGetChunkMixin can send them a Russian message when their
 * teleport destination chunk is not yet loaded.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerCommandTrackerMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), require = 0)
    private void voidrp_setCommandPlayer(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        CurrentCommandPlayer.set(this.player);
    }

    @Inject(method = "handleChatCommand", at = @At("RETURN"), require = 0)
    private void voidrp_clearCommandPlayer(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        CurrentCommandPlayer.clear();
    }
}
