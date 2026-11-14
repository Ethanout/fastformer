package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.common.selection.session.OperationSession;
import io.github.fastformer.server.sync.PreviewSync;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class SelectionServerMode extends ServerMode<OperationSession> {
   private final OperationSystem selections;

   SelectionServerMode(OperationSystem selections) {
      super(BuiltinModes.SELECTION, InteractionState.OPERATION, selections);
      this.selections = selections;
   }

   @Override public boolean acceptsHeldItem(ServerPlayer player) { return player.getMainHandItem().isEmpty(); }
   @Override protected boolean allowsCommandAdjust(ServerPlayer player, OperationSession session) { return session.selectionReady(); }
   @Override protected void onPreview(ServerPlayer player, OperationSession session) { PreviewSync.syncOperation(player, session); }
   @Override protected IntentResult onCycle(ServerPlayer player, OperationSession session, BlockPos lineCandidate) {
      return onIdleCycle(player, lineCandidate);
   }
   @Override protected IntentResult onIdleCycle(ServerPlayer player, BlockPos lineCandidate) {
      selections.cycleMode(player);
      return IntentResult.success();
   }
   @Override protected void onAdjust(ServerPlayer player, OperationSession session, int steps) { selections.scroll(player, steps); }
   @Override protected IntentResult onConfirm(ServerPlayer player, OperationSession session) { return IntentResult.handled(selections.applyConfirmed(player, false)); }
   @Override public LongRangeBlockRaycast.Result raycast(ServerPlayer player, Vec3 eye, Vec3 view, boolean modifierHeld) {
      return LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, modifierHeld);
   }
}
