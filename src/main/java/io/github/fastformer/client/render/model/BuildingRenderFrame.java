package io.github.fastformer.client.render.model;

import io.github.fastformer.fastplace.geometry.ControlPoint;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Derived building-preview data that remains stable until its render key changes. */
public record BuildingRenderFrame(
   BuildingRenderLayers layers,
   BuildingRenderLayers shellLayers,
   List<ControlPoint> controlPoints,
   Map<BlockPos, BuildingSpecialBlock> specialBlockStyles,
   Map<BlockPos, BlockState> stateOverrides
) {
   public BuildingRenderFrame {
      controlPoints = List.copyOf(controlPoints);
      specialBlockStyles = Map.copyOf(specialBlockStyles);
      stateOverrides = Map.copyOf(stateOverrides);
   }

   public static BuildingRenderFrame empty() {
      BuildingRenderLayers empty = BuildingRenderLayers.empty();
      return new BuildingRenderFrame(empty, empty, List.of(), Map.of(), Map.of());
   }
}
