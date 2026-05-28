package ru.voidrp.asyncai;

import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Задерживает телепортацию игрока в незагруженный чанк до тех пор,
 * пока чанк не будет готов или не истечёт таймаут.
 *
 * Поведение:
 *  - При перехвате: немедленно запускает загрузку чанков через ticket и ставит
 *    телепортацию в очередь. Игроку показывается боссбар с прогрессом загрузки.
 *  - Каждый тик: проверяет, загрузился ли целевой чанк.
 *    Как только чанк готов (или через 10 секунд) — выполняет телепортацию.
 *  - Флаг EXECUTING_QUEUED предотвращает повторный перехват при выполнении очереди.
 */
public final class TeleportQueueManager {

    /** Устанавливается в true перед выполнением отложенного /tp, чтобы mixin его не перехватил. */
    public static final ThreadLocal<Boolean> EXECUTING_QUEUED = ThreadLocal.withInitial(() -> false);

    private static final int MAX_WAIT_TICKS = 200; // 10 секунд
    private static final int TICKET_RADIUS  = 3;   // радиус загрузки чанков вокруг цели

    // Только main-thread — обычный HashMap достаточен
    private static final Map<UUID, PendingTeleport> pending = new HashMap<>();

    private static final class PendingTeleport {
        final ServerLevel level;
        final double x, y, z;
        final Set<RelativeMovement> relativeMovements;
        final float yRot, xRot;
        final ServerBossEvent bossBar;
        int ticksWaiting;

        PendingTeleport(ServerLevel level, double x, double y, double z,
                        Set<RelativeMovement> relativeMovements, float yRot, float xRot,
                        ServerPlayer player) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.relativeMovements = relativeMovements;
            this.yRot = yRot;
            this.xRot = xRot;

            this.bossBar = new ServerBossEvent(
                Component.literal("⏳ Прогружаю чанки... 10 сек."),
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarOverlay.PROGRESS
            );
            this.bossBar.setProgress(0.0f);
            this.bossBar.addPlayer(player);
        }
    }

    private TeleportQueueManager() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Проверяет, загружен ли целевой чанк. Если нет — ставит телепортацию в очередь и
     * запускает загрузку чанков. Возвращает true, если телепорт поставлен в очередь
     * (вызывающий должен отменить оригинальный вызов).
     */
    public static boolean tryQueue(ServerPlayer player,
                                   ServerLevel level, double x, double y, double z,
                                   Set<RelativeMovement> relativeMovements, float yRot, float xRot) {
        int cx = SectionPos.blockToSectionCoord((int) Math.floor(x));
        int cz = SectionPos.blockToSectionCoord((int) Math.floor(z));

        if (level.getChunkSource().getChunkNow(cx, cz) != null) {
            return false; // чанк уже загружен, телепортируем сразу
        }

        // Запускаем загрузку чанков через ticket-систему (не блокирует main-thread)
        ChunkPos destChunk = new ChunkPos(cx, cz);
        level.getChunkSource().addRegionTicket(TicketType.UNKNOWN, destChunk, TICKET_RADIUS, destChunk);

        PendingTeleport pt = new PendingTeleport(level, x, y, z, relativeMovements, yRot, xRot, player);
        pending.put(player.getUUID(), pt);

        VoidRpAsyncAI.LOGGER.info(
            "[VoidRP Teleport] {} → [{}, {}] — чанк [{},{}] ещё не загружен, ставим в очередь",
            player.getGameProfile().getName(),
            (int) x, (int) z, cx, cz);
        return true;
    }

    // -------------------------------------------------------------------------
    // Tick loop — вызывается из VoidRpAsyncAI.onServerTick
    // -------------------------------------------------------------------------

    public static void onTick(MinecraftServer server) {
        if (pending.isEmpty()) return;

        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, PendingTeleport> entry : new ArrayList<>(pending.entrySet())) {
            UUID uuid = entry.getKey();
            PendingTeleport pt = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null || player.isRemoved()) {
                removeBossBar(pt, null);
                toRemove.add(uuid);
                continue;
            }

            pt.ticksWaiting++;

            int cx = SectionPos.blockToSectionCoord((int) Math.floor(pt.x));
            int cz = SectionPos.blockToSectionCoord((int) Math.floor(pt.z));

            boolean chunkLoaded = pt.level.getChunkSource().getChunkNow(cx, cz) != null;
            boolean timeout     = pt.ticksWaiting >= MAX_WAIT_TICKS;

            // Update boss bar progress every tick
            float progress = (float) pt.ticksWaiting / MAX_WAIT_TICKS;
            pt.bossBar.setProgress(Math.min(1.0f, progress));

            // Update boss bar title every second
            if (pt.ticksWaiting % 20 == 0) {
                int remaining = Math.max(1, 10 - (pt.ticksWaiting / 20));
                pt.bossBar.setName(Component.literal("⏳ Прогружаю чанки... " + remaining + " сек."));
            }

            if (chunkLoaded || timeout) {
                doTeleport(player, pt);
                toRemove.add(uuid);
            }
        }

        toRemove.forEach(pending::remove);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public static void cancelForPlayer(UUID uuid) {
        PendingTeleport pt = pending.remove(uuid);
        if (pt != null) removeBossBar(pt, null);
    }

    public static void onPlayerLeave(UUID uuid) {
        PendingTeleport pt = pending.remove(uuid);
        if (pt != null) removeBossBar(pt, null);
    }

    public static void shutdown() {
        pending.values().forEach(pt -> removeBossBar(pt, null));
        pending.clear();
    }

    public static int getPendingCount() {
        return pending.size();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static void doTeleport(ServerPlayer player, PendingTeleport pt) {
        EXECUTING_QUEUED.set(true);
        try {
            player.teleportTo(pt.level, pt.x, pt.y, pt.z, pt.relativeMovements, pt.yRot, pt.xRot);
            VoidRpAsyncAI.LOGGER.info(
                "[VoidRP Teleport] {} телепортирован в [{}, {}] (ожидание {} тик.)",
                player.getGameProfile().getName(), (int) pt.x, (int) pt.z, pt.ticksWaiting);
        } catch (Exception e) {
            VoidRpAsyncAI.LOGGER.warn(
                "[VoidRP Teleport] ошибка при телепортации {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        } finally {
            EXECUTING_QUEUED.set(false);
            removeBossBar(pt, player);
        }
    }

    private static void removeBossBar(PendingTeleport pt, ServerPlayer player) {
        try {
            pt.bossBar.setProgress(1.0f);
            pt.bossBar.removeAllPlayers();
        } catch (Exception ignored) {}
    }
}
