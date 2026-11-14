package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class SelectionServerMode extends ServerMode {
   private final OperationSystem selections;

   SelectionServerMode(OperationSystem selections) {
      super(BuiltinModes.SELECTION, InteractionState.OPERATION);
      this.selections = selections;
   }

   @Override public boolean active(ServerPlayer player) { return selections.active(player); }
   @Override public boolean acceptsHeldItem(ServerPlayer player) { return player.getMainHandItem().isEmpty(); }
   @Override public boolean allowsCommandAdjust(ServerPlayer player) { return selections.selectionReady(player); }
   @Override public void sync(ServerPlayer player) { selections.sync(player); }
   @Override public IntentResult cycle(ServerPlayer player, BlockPos lineCandidate) {
      selections.cycleMode(player);
      return IntentResult.success();
   }
   @Override public void adjust(ServerPlayer player, int steps) { selections.scroll(player, steps); }
   @Override public IntentResult confirm(ServerPlayer player) { return IntentResult.handled(selections.applyConfirmed(player, false)); }
   @Override public LongRangeBlockRaycast.Result raycast(ServerPlayer player, Vec3 eye, Vec3 view, boolean modifierHeld) {
      return LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, modifierHeld);
   }
}
