package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.quickshape.QuickShapeDraft;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.session.QuickShapeSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class QuickShapeServerMode extends ServerMode<QuickShapeDraft> {
   private final QuickShapeSystem shapes;
   private final java.util.function.Consumer<ServerPlayer> quit;

   QuickShapeServerMode(QuickShapeSystem shapes, java.util.function.Consumer<ServerPlayer> quit) {
      super(BuiltinModes.QUICK_SHAPE, InteractionState.BUILDING, shapes);
      this.shapes = shapes;
      this.quit = quit;
   }

   @Override public boolean acceptsHeldItem(ServerPlayer player) { return shapes.acceptsHeldItem(player); }
   @Override protected void onOpen(ServerPlayer player, QuickShapeDraft draft) {
      shapes.initialize(player, draft);
   }
   @Override protected void onPreview(ServerPlayer player, QuickShapeDraft draft) {
      io.github.fastformer.server.sync.PreviewSync.syncPreview(player, draft);
   }

   @Override protected boolean onRightClickBlock(ServerPlayer player, QuickShapeDraft draft, BlockHitResult hit) {
      return onIdleRightClickBlock(player, hit);
   }

   @Override protected boolean onIdleRightClickBlock(ServerPlayer player, BlockHitResult hit) {
      if (!acceptsHeldItem(player)) {
         quit.accept(player);
         return false;
      }
      shapes.addPoint(player, hit);
      return true;
   }

   @Override protected boolean onRightClickItem(ServerPlayer player, QuickShapeDraft draft, BlockHitResult hit) {
      if (hit.getType() == HitResult.Type.BLOCK) shapes.addPoint(player, hit);
      else {
         BlockPos first = draft.points().isEmpty() ? BlockPos.ZERO : draft.points().getFirst();
         BlockPos anchor = first.offset(draft.freeScrollOffset());
         shapes.addPoint(player, anchor, anchor);
      }
      return true;
   }

   @Override protected boolean onIdleRightClickItem(ServerPlayer player, BlockHitResult hit) {
      if (hit.getType() != HitResult.Type.BLOCK) return false;
      shapes.addPoint(player, hit);
      return true;
   }

   @Override protected IntentResult onCycle(ServerPlayer player, QuickShapeDraft draft, BlockPos lineCandidate) {
      return onIdleCycle(player, lineCandidate);
   }
   @Override protected IntentResult onIdleCycle(ServerPlayer player, BlockPos lineCandidate) {
      shapes.cycleStageMode(player, lineCandidate);
      return IntentResult.success();
   }

   @Override protected void onAdjust(ServerPlayer player, QuickShapeDraft draft, int steps) { shapes.scrollContext(player, steps); }
   @Override protected void onIdleAdjust(ServerPlayer player, int steps) { shapes.scrollContext(player, steps); }
   @Override protected IntentResult onConfirm(ServerPlayer player, QuickShapeDraft draft) {
      shapes.fill(player);
      return IntentResult.success();
   }

   @Override public LongRangeBlockRaycast.Result raycast(ServerPlayer player, Vec3 eye, Vec3 view, boolean modifierHeld) {
      return LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view);
   }

}
