package io.github.fastformer.server.gate;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import io.github.fastformer.common.mode.BuiltinModes;
import net.minecraft.server.level.ServerPlayer;

/** Shared admission policies. Cancellation and recovery deliberately do not require editing admission. */
public final class ServerOperationGates {
   public static final Gate<ServerPlayer> AVAILABLE = Gate.<ServerPlayer>require("creative", ServerPlayer::isCreative)
      .and(Gate.require("enabled", player -> FastPlaceSettings.load(player).enabled()))
      .and(Gate.require("recovery", player -> RecoverySystem.of(player.getServer()).writeGate().writesAllowed()));

   public static final Gate<ServerPlayer> WRITE_AVAILABLE = Gate.<ServerPlayer>require("placement_task", player -> !SessionSystem.of(player).placementTasks().active(player))
      .and(Gate.require("selection_task", player -> !SessionSystem.of(player).operationTasks().active(player)))
      .and(Gate.require("smart_fill", player -> !SessionSystem.of(player).modes().active(player, BuiltinModes.SMART_FILL)))
      .and(Gate.require("history", player -> !HistorySystem.of(player).busy(player)));
   public static final Gate<ServerPlayer> WORLD_UNLOCKED = Gate.require("world_write",
      player -> !RecoverySystem.of(player.getServer()).writeLeases().busy(player.serverLevel().dimension()));
   public static final Gate<ServerPlayer> START_WRITE = WRITE_AVAILABLE.and(WORLD_UNLOCKED);
   public static final Gate<ServerPlayer> CONTINUE_INTERACTION = WRITE_AVAILABLE.and(
      WORLD_UNLOCKED.or(Gate.require("observed_transaction", BlockEditTransaction::continuingObservedAction)));
   public static final Gate<ServerPlayer> OPERATE = AVAILABLE.and(START_WRITE);
   public static final Gate<ServerPlayer> NO_SESSION = Gate.require("active_session",
      player -> !SessionSystem.of(player).modes().hasEditing(player));
   public static final Gate<ServerPlayer> WORLD_HISTORY = AVAILABLE.and(WRITE_AVAILABLE).and(NO_SESSION);

   private ServerOperationGates() {}
}
