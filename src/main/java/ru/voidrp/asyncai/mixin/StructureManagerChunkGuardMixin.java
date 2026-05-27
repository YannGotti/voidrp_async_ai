package ru.voidrp.asyncai.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.voidrp.asyncai.VoidRpAsyncAI;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards StructureManager structure lookups against crash during chunk decoration.
 *
 * Root cause #1 (2026-05-26 12:37): ValkyrieQueen.finalizeSpawn() → getStructureAt
 * Root cause #2 (2026-05-27 12:00): wetland_whimsy$WitchSetPersistance + FriendsAndFoes
 *   IllusionerEntity.finalizeSpawn() → Raider.finalizeSpawn
 *   → getStructureWithPieceAt(BlockPos,Structure)  ← 2-arg, no SectionPos overload
 *   → startsForStructure(SectionPos,Structure) → Level.getChunk → IllegalStateException
 *
 * NOTE: There is NO (BlockPos,SectionPos,Structure) overload in StructureManager.
 *       The 2-arg getStructureWithPieceAt(BlockPos,Structure) directly calls startsForStructure.
 */
@Mixin(StructureManager.class)
public abstract class StructureManagerChunkGuardMixin {

    private static final ConcurrentHashMap<Long, Long> WARN_TIMESTAMPS = new ConcurrentHashMap<>();
    private static final long WARN_COOLDOWN_MS = 10_000L;

    private static boolean shouldWarn(int x, int z) {
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        long now = System.currentTimeMillis();
        Long last = WARN_TIMESTAMPS.put(key, now);
        return last == null || now - last >= WARN_COOLDOWN_MS;
    }

    @Redirect(
        method = "getStructureAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"
        ),
        require = 0
    )
    private List<StructureStart> voidrp_safeStartsForStructureAt(
            StructureManager instance, SectionPos sectionPos, Structure structure) {
        try {
            return instance.startsForStructure(sectionPos, structure);
        } catch (Exception e) {
            if (shouldWarn(sectionPos.x(), sectionPos.z())) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] StructureManager chunk guard (getStructureAt) — lookup failed at" +
                    " section [{},{}] during chunk decoration ({}), skipping",
                    sectionPos.x(), sectionPos.z(), e.getMessage());
            }
            return List.of();
        }
    }

    // Root cause #2: 2-arg (BlockPos,Structure) directly calls startsForStructure(SectionPos,Structure)
    @Redirect(
        method = "getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/StructureManager;startsForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;)Ljava/util/List;"
        ),
        require = 0
    )
    private List<StructureStart> voidrp_safeStartsForStructureWithPieceAt(
            StructureManager instance, SectionPos sectionPos, Structure structure) {
        try {
            return instance.startsForStructure(sectionPos, structure);
        } catch (Exception e) {
            if (shouldWarn(sectionPos.x(), sectionPos.z())) {
                VoidRpAsyncAI.LOGGER.warn(
                    "[VoidRP] StructureManager chunk guard (getStructureWithPieceAt) — lookup failed at" +
                    " section [{},{}] during chunk decoration ({}), skipping",
                    sectionPos.x(), sectionPos.z(), e.getMessage());
            }
            return List.of();
        }
    }
}
