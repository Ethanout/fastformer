package io.github.fastformer.fastplace;

import io.github.fastformer.network.FastPlaceNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FastPlaceManager {
    private static final Map<UUID, FastPlaceSession> SESSIONS = new HashMap<>();

    private FastPlaceManager() {
    }

    public static Optional<FastPlaceSession> session(ServerPlayer player) {
        return Optional.ofNullable(SESSIONS.get(player.getUUID()));
    }

    public static boolean active(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    public static void addPoint(ServerPlayer player, BlockPos point) {
        FastPlaceSession session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new FastPlaceSession());
        boolean closed = session.addOrClose(point);
        if (closed) {
            FastPlaceMessages.actionBar(player, "FastFormer: closed " + session.points().size() + " points");
            cancel(player);
            return;
        }
        showStatus(player, session);
        FastPlaceNetwork.syncPreview(player, session);
    }

    public static void undo(ServerPlayer player) {
        FastPlaceSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        if (!session.undo()) {
            cancel(player);
            FastPlaceMessages.actionBar(player, "FastFormer: cancelled");
            return;
        }
        showStatus(player, session);
        FastPlaceNetwork.syncPreview(player, session);
    }

    public static void cancel(ServerPlayer player) {
        if (SESSIONS.remove(player.getUUID()) != null) {
            FastPlaceNetwork.clearPreview(player);
        }
    }

    public static void quit(ServerPlayer player) {
        cancel(player);
        FastPlaceMessages.actionBar(player, "FastFormer: quit");
    }

    public static void cycleFill(ServerPlayer player) {
        FillMode fillMode = FastPlaceSettings.load(player).cycleFillMode(player);
        FastPlaceMessages.actionBar(player, "FastFormer fill: " + fillMode.displayName());
        syncPreviewIfActive(player);
    }

    public static void cycleStageMode(ServerPlayer player) {
        FastPlaceSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        FastPlaceSettings settings = FastPlaceSettings.load(player);
        FastPlaceMode mode = settings.cycleMode(player, session.stage());
        FastPlaceMessages.actionBar(player, "FastFormer mode: " + mode.displayName());
        FastPlaceNetwork.syncPreview(player, session);
    }

    public static void remove(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    public static void restoreTossedItem(ServerPlayer player, ItemStack tossed) {
        if (tossed.isEmpty()) {
            return;
        }

        Inventory inventory = player.getInventory();
        ItemStack selected = inventory.getSelected();
        ItemStack returning = tossed.copy();
        if (selected.isEmpty()) {
            inventory.setItem(inventory.selected, returning);
        } else if (ItemStack.isSameItemSameComponents(selected, returning)
                && selected.getCount() + returning.getCount() <= selected.getMaxStackSize()) {
            selected.grow(returning.getCount());
            inventory.setItem(inventory.selected, selected);
        } else if (!inventory.add(returning)) {
            player.drop(returning, false);
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }

    private static void showStatus(ServerPlayer player, FastPlaceSession session) {
        FastPlaceSettings settings = FastPlaceSettings.load(player);
        FastPlaceStage stage = session.stage();
        FastPlaceMessages.actionBar(player, "FastFormer: "
                + session.points().size()
                + " points | "
                + stage.displayName()
                + " | "
                + settings.modeFor(stage).displayName()
                + " | "
                + settings.fillMode().displayName());
    }

    private static void syncPreviewIfActive(ServerPlayer player) {
        FastPlaceSession session = SESSIONS.get(player.getUUID());
        if (session != null) {
            FastPlaceNetwork.syncPreview(player, session);
        }
    }
}
