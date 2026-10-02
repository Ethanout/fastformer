package io.github.fastformer.client.interaction;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.selection.SmartSelectionRaycast;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Preview and input share the hit cell and its adjacent face cell for Alt edits. */
public final class SmartSelectionEditView {
   private SmartSelectionEditView() { }

   public static boolean active(Minecraft minecraft) {
      return minecraft != null && minecraft.level != null && minecraft.player != null && minecraft.screen == null
         && ClientOperationController.smartTool() && ClientOperationController.selectionSessionActive()
         && FastPlaceClientInput.modifierHeld()
         && ClientOperationController.workspace().parts().stream().anyMatch(part -> part.smartEditable());
   }

   public record Target(int partId, SmartSelectionRaycast.Pick pick) {
      public BlockPos adjacent() { return SmartSelectionRaycast.adjacent(pick); }
   }

   public static BlockPos additionTarget(Minecraft minecraft, Target target) {
      if (target == null || minecraft.level == null) return null;
      var position = target.adjacent();
      if (minecraft.level.isOutsideBuildHeight(position) || !minecraft.level.hasChunkAt(position)) return null;
      return position;
   }

   public static BlockHitResult worldTarget(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null || minecraft.level == null) return null;
      var hit = LongRangeBlockRaycast.clipForSelection(minecraft.level, minecraft.player,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), FastPlaceClientInput.modifierHeld()).hit();
      return hit.getType() == HitResult.Type.BLOCK ? hit : null;
   }

   public static Target target(Minecraft minecraft, boolean throughWorld) {
      if (minecraft == null || minecraft.player == null || minecraft.level == null) return null;
      var eye = minecraft.player.getEyePosition();
      var view = minecraft.player.getViewVector(1.0F);
      double reach = LongRangeBlockRaycast.MAX_REACH;
      if (!throughWorld) {
         var worldHit = worldTarget(minecraft);
         if (worldHit != null) reach = eye.distanceTo(worldHit.getLocation()) + 0.02;
      }
      Target closest = null;
      for (var part : ClientOperationController.workspace().parts().reversed()) {
         if (!part.smartEditable()) continue;
         var hit = throughWorld ? SmartSelectionRaycast.pickCells(part.blocks().keySet(), eye, view, reach)
            : SmartSelectionRaycast.pick(part.blocks(), minecraft.level, CollisionContext.of(minecraft.player), eye, view, reach);
         if (hit != null && (closest == null || hit.hit().distance() < closest.pick().hit().distance())) {
            closest = new Target(part.id(), hit);
            reach = hit.hit().distance();
         }
      }
      return closest;
   }
}
