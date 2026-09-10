package io.github.fastformer.fastplace.placement.effect;

import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.PolygonVolumeShape;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable inputs available to every placement effect. */
public record PlacementEffectContext(
   Player player,
   ItemStack heldItem,
   BlockState prototype,
   Direction.Axis baseAxis,
   List<BlockPos> points,
   FastPlaceGeometry.Modes modes,
   boolean polygonHeightConfirmed,
   PolygonVolumeShape polygonVolumeShape,
   PlacementContextSnapshot placementContext
) {
   public PlacementEffectContext {
      heldItem = heldItem == null ? ItemStack.EMPTY : heldItem.copy();
      baseAxis = baseAxis == null ? Direction.Axis.Y : baseAxis;
      points = points == null ? List.of() : List.copyOf(points);
      polygonVolumeShape = polygonVolumeShape == null ? PolygonVolumeShape.EXTRUDE : polygonVolumeShape;
   }
}
