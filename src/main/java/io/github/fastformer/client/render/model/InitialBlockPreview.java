package io.github.fastformer.client.render.model;

import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPointStyle;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

public record InitialBlockPreview(BlockPos position, BlockState state, ShapeShellMesh.Mesh mesh) {
   public static InitialBlockPreview resolve(Player player, BlockHitResult hit, boolean embedded) {
      if (hit.getType() != HitResult.Type.BLOCK) return null;
      boolean emptyHand = player.getMainHandItem().isEmpty();
      var context = PlacementContextSnapshot.capture(
         player.level(), player, player.getMainHandItem(), hit, embedded || emptyHand
      );
      BlockPos position = embedded || emptyHand ? hit.getBlockPos() : context.placementPosition();
      BlockState state = emptyHand ? player.level().getBlockState(position)
         : PlaceableItems.placementState(player.getMainHandItem(), player, context).orElse(null);
      if (state == null) return null;
      var level = emptyHand ? player.level() : PreviewBlockOcclusion.level(Set.of(position), state, Map.of());
      var boxes = state.getShape(level, position, CollisionContext.of(player)).toAabbs();
      return new InitialBlockPreview(position, state, mesh(position, boxes));
   }

   public static ShapeShellMesh.Mesh mesh(BlockPos position, List<AABB> boxes) {
      var style = ControlPointStyle.START;
      var color = new ShapeShellMesh.Color(style.red(), style.green(), style.blue());
      return ShapeShellMesh.build(List.of(new ShapeShellMesh.Part(
         boxes.stream().map(box -> box.move(position)).toList(), color, color, true
      )));
   }
}
