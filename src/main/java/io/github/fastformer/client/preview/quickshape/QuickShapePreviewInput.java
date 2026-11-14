package io.github.fastformer.client.preview.quickshape;

import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.common.placement.PlacementContextSnapshot;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Placement and pointer facts captured before quick-shape model construction. */
public record QuickShapePreviewInput(BuildingPreviewSnapshot snapshot, LocalPlayer player, Vec3 eye, Vec3 view,
   BlockPos candidate, BlockPos hoveredPoint, BlockState prototype, PlacementContextSnapshot placementContext) {
   public QuickShapePreviewInput {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(player, "player");
      Objects.requireNonNull(eye, "eye");
      Objects.requireNonNull(view, "view");
   }
}
