package io.github.fastformer.client.preview.quickshape;

import io.github.fastformer.client.render.model.BuildingRenderFrame;
import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.GuidePlane;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Generated membership, markers, guides, and fallback boundaries for one quick-shape frame. */
public record QuickShapePreviewModel(LocalPlayer player, BlockState prototype, BuildingRenderFrame blocks,
   List<GuidePlane> guides, List<GuideLine> confirmedBoundary, List<GuideLine> pendingBoundary,
   boolean confirmedLightweight, boolean pendingLightweight) {
   public QuickShapePreviewModel {
      guides = List.copyOf(guides);
      confirmedBoundary = List.copyOf(confirmedBoundary);
      pendingBoundary = List.copyOf(pendingBoundary);
   }
}
