package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.voidrp.asyncai.ChunkWarnRateLimit;
import ru.voidrp.asyncai.CurrentCommandPlayer;
import ru.voidrp.asyncai.VoidRpAsyncAI;

/**
 * Guards CraftWorld.getChunkAt(int, int) against main-thread deadlock
 * when the destination chunk is not yet loaded.
 *
 * Root cause (Watchdog dump 2026-05-27 11:48):
 *   EssentialsX AsyncTeleport.nowAsync() → PaperLib.getChunkAtAsync
 *   → AsyncChunksSync.getChunkAtAsync (sync fallback — PaperLib does not detect Youer as Paper)
 *   → CraftWorld.getChunkAt → Level.getChunk → ServerChunkCache.getChunk(require=true)
 *   → managedBlock → waitForTasks → parkNanos
 *   Distant chunk generation takes 10-60+ seconds, Watchdog fires at 10 s → auto-restart.
 *
 * Fix: when getChunkAt is called from the main thread for a not-yet-loaded chunk:
 *   1. Start real async chunk loading via NeoForge ticket system (addRegionTicket).
 *   2. Return the world spawn chunk as a placeholder — it is always loaded.
 *      EssentialsX only checks that the future completed non-null, then teleports using
 *      the original Location (NOT the returned Chunk reference), so the placeholder is safe.
 *   3. EssentialsX proceeds to call player.teleport(location) → ServerPlayer.teleportTo()
 *      with the correct full target coordinates (including Y).
 *   4. TeleportInterceptMixin intercepts teleportTo(), sees destination chunk unloaded,
 *      queues in TeleportQueueManager with full coords, shows actionbar countdown.
 *   5. TeleportQueueManager teleports the player automatically once the chunk is ready.
 *
 * Fallback: if spawn chunk is unavailable (edge case), throws RuntimeException with a
 * clear Russian message rather than returning null (which causes NPE downstream).
 */
@Mixin(targets = "org.bukkit.craftbukkit.CraftWorld", remap = false)
public abstract class CraftWorldGetChunkMixin {

    @Shadow public abstract ServerLevel getHandle();

    @Inject(
        method = "getChunkAt(II)Lorg/bukkit/Chunk;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void voidrp_guardGetChunkAt(int x, int z, CallbackInfoReturnable<Object> cir) {
        if (!"Server thread".equals(Thread.currentThread().getName())) return;

        ServerLevel level = getHandle();
        if (level.getChunkSource().getChunkNow(x, z) != null) return; // already loaded, fall through safely

        // 1. Queue the destination chunk for async generation via NeoForge ticket system.
        //    addRegionTicket is non-blocking: it posts to DistanceManager and returns immediately.
        //    Generation runs on vanilla's thread pool — zero main-thread cost.
        ChunkPos destPos = new ChunkPos(x, z);
        level.getChunkSource().addRegionTicket(TicketType.UNKNOWN, destPos, 1, destPos);

        // 2. Return the world spawn chunk as a non-null placeholder.
        //    EssentialsX/PaperLib thenAccept callback only checks chunk != null, then
        //    teleports to the original Location. TeleportInterceptMixin takes it from there.
        try {
            BlockPos spawnPos = level.getSharedSpawnPos();
            int spawnCx = SectionPos.blockToSectionCoord(spawnPos.getX());
            int spawnCz = SectionPos.blockToSectionCoord(spawnPos.getZ());
            LevelChunk spawnChunk = level.getChunkSource().getChunkNow(spawnCx, spawnCz);
            if (spawnChunk != null) {
                Object bukkitChunk = spawnChunk.getClass().getMethod("getBukkitChunk").invoke(spawnChunk);
                if (bukkitChunk != null) {

                    var cmdPlayer = CurrentCommandPlayer.get();
                    if (cmdPlayer != null) {
                        cmdPlayer.displayClientMessage(
                            Component.literal("§6⏳ Прогружаю чанки... §eТелепортирую автоматически."),
                            true
                        );
                    }

                    long suppressed = ChunkWarnRateLimit.acquire(x, z);
                    if (suppressed >= 0) {
                        VoidRpAsyncAI.LOGGER.info(
                            "[VoidRP] CraftWorld.getChunkAt — chunk [{},{}] queued for load, " +
                            "returning spawn placeholder; teleport will auto-complete{}.",
                            x, z, suppressed > 0 ? " (+" + suppressed + " suppressed)" : "");
                    }

                    cir.setReturnValue(bukkitChunk);
                    return;
                }
            }
        } catch (Exception e) {
            VoidRpAsyncAI.LOGGER.warn("[VoidRP] CraftWorld.getChunkAt getBukkitChunk failed: {}", e.getMessage());
        }

        // Fallback if spawn chunk is unavailable — clear message, not NPE.
        throw new RuntimeException("Чанки ещё не загружены — повтори команду через ~10 секунд");
    }
}
