package io.github.fastformer.server.gate;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.fill.SmartFillSystem;
import net.minecraft.server.level.ServerPlayer;

/** Shared admission policies. Cancellation and recovery deliberately do not require editing admission. */
public final class ServerOperationGates {
   public static final Gate<ServerPlayer> AVAILABLE = Gate.<ServerPlayer>require("creative", ServerPlayer::isCreative)
      .and(Gate.require("enabled", player -> FastPlaceSettings.load(player).enabled()))
      .and(Gate.require("recovery", player -> RecoverySystem.of(player.getServer()).writeGate().writesAllowed()));

   public static final Gate<ServerPlayer> WRITE_AVAILABLE = Gate.<ServerPlayer>require("placement_task", player -> !QuickShapeSystem.of(player).taskActive(player))
      .and(Gate.require("selection_task", player -> !OperationSystem.of(player).taskActive(player)))
      .and(Gate.require("smart_fill", player -> !SmartFillSystem.active(player)))
      .and(Gate.require("history", player -> !HistorySystem.of(player).busy(player)));
   public static final Gate<ServerPlayer> WORLD_UNLOCKED = Gate.require("world_write",
      player -> !RecoverySystem.of(player.getServer()).writeLeases().busy(player.serverLevel().dimension()));
   public static final Gate<ServerPlayer> START_WRITE = WRITE_AVAILABLE.and(WORLD_UNLOCKED);
   public static final Gate<ServerPlayer> CONTINUE_INTERACTION = WRITE_AVAILABLE.and(
      WORLD_UNLOCKED.or(Gate.require("observed_transaction", BlockEditTransaction::continuingObservedAction)));
   public static final Gate<ServerPlayer> OPERATE = AVAILABLE.and(START_WRITE);
   public static final Gate<ServerPlayer> NO_SESSION = Gate.require("active_session",
      player -> !QuickShapeSystem.of(player).active(player) && !OperationSystem.of(player).active(player) && !GeometrySystem.of(player).active(player));
   public static final Gate<ServerPlayer> WORLD_HISTORY = AVAILABLE.and(WRITE_AVAILABLE).and(NO_SESSION);

   private ServerOperationGates() {}
}
