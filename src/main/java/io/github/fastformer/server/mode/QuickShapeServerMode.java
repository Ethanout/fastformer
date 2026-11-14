package io.github.fastformer.server.mode;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.quickshape.QuickShapeDraft;
import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.session.QuickShapeSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class QuickShapeServerMode extends ServerMode {
   private final QuickShapeSystem shapes;
   private final java.util.function.Consumer<ServerPlayer> quit;

   QuickShapeServerMode(QuickShapeSystem shapes, java.util.function.Consumer<ServerPlayer> quit) {
      super("quick_shape", InteractionState.BUILDING);
      this.shapes = shapes;
      this.quit = quit;
   }

   @Override public boolean active(ServerPlayer player) { return shapes.active(player); }
   @Override public boolean acceptsHeldItem(ServerPlayer player) { return shapes.acceptsHeldItem(player); }
   @Override public void sync(ServerPlayer player) { shapes.sync(player); }

   @Override public boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) {
      if (!acceptsHeldItem(player)) {
         quit.accept(player);
         return false;
      }
      shapes.addPoint(player, hit);
      return true;
   }

   @Override public boolean rightClickItem(ServerPlayer player, BlockHitResult hit) {
      if (hit.getType() == HitResult.Type.BLOCK) shapes.addPoint(player, hit);
      else {
         QuickShapeDraft draft = shapes.session(player).orElse(null);
         if (draft == null) return false;
         BlockPos first = draft.points().isEmpty() ? BlockPos.ZERO : draft.points().getFirst();
         BlockPos anchor = first.offset(draft.freeScrollOffset());
         shapes.addPoint(player, anchor, anchor);
      }
      return true;
   }

   @Override public IntentResult cycle(ServerPlayer player, BlockPos lineCandidate) {
      shapes.cycleStageMode(player, lineCandidate);
      return IntentResult.success();
   }

   @Override public void adjust(ServerPlayer player, int steps) { shapes.scrollContext(player, steps); }
   @Override public IntentResult confirm(ServerPlayer player) {
      if (!active(player)) return IntentResult.pass();
      shapes.fill(player);
      return IntentResult.success();
   }

   @Override public LongRangeBlockRaycast.Result raycast(ServerPlayer player, Vec3 eye, Vec3 view, boolean modifierHeld) {
      return LongRangeBlockRaycast.clipForPlacement(player.level(), player, eye, view);
   }

}
