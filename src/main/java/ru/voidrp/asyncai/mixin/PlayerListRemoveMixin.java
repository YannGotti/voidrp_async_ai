package ru.voidrp.asyncai.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.voidrp.asyncai.DisconnectContext;
import ru.voidrp.asyncai.PlayerSaveWorker;

/**
 * Makes player data saves synchronous during disconnect (PlayerList.remove).
 *
 * Why this is necessary:
 *   During autosave, saves are offloaded to a background thread (PlayerDataSaveAsyncMixin).
 *   If a player disconnects while such an async write is still in flight, and the server
 *   crashes immediately after, their newest data (from the disconnect save) would be lost.
 *
 * This mixin does two things when a player disconnects:
 *   1. Waits for any in-flight async write for this player to complete first,
 *      so the disconnect's synchronous write goes last and wins.
 *   2. Sets DisconnectContext so PlayerDataSaveAsyncMixin skips async and writes directly.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListRemoveMixin {

    @Inject(method = "remove", at = @At("HEAD"), require = 0)
    private void voidrp_beforeRemove(ServerPlayer player, CallbackInfo ci) {
        // Wait for any pending autosave write for this player to finish.
        // The disconnect save (synchronous) will then run after and write the freshest data.
        PlayerSaveWorker.awaitPendingForPlayer(player.getUUID());
        DisconnectContext.set(true);
    }

    @Inject(method = "remove", at = @At("RETURN"), require = 0)
    private void voidrp_afterRemove(ServerPlayer player, CallbackInfo ci) {
        DisconnectContext.set(false);
    }
}
