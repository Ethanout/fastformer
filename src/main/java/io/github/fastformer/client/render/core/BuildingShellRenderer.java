package io.github.fastformer.client.render.core;

import static io.github.fastformer.client.render.core.PreviewRenderResources.*;
import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Draws building shells with explicit frame opacity and animation phase. */
final class BuildingShellRenderer {
   private BuildingShellRenderer() {}

   static BuildingShellVisibility render(
      LocalPlayer player,
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      BlockState state,
      Map<BlockPos, BlockState> stateOverrides,
      Set<BlockPos> confirmedBlocks,
      Set<BlockPos> pendingBlocks,
      Map<BlockPos, BuildingSpecialBlock> specialStyles,
      float worldPreviewOpacity, float breathPulse,
      boolean confirmedLightweight,
      boolean pendingLightweight,
      boolean cleanOutlineEdges
   ) {
      if (confirmedLightweight) {
         CONFIRMED_BUILDING_SHELL_CACHE.clear();
         CONFIRMED_SHELL_FACES.clear();
         CONFIRMED_SHELL_EDGES.clear();
      }
      if (pendingLightweight) {
         PENDING_BUILDING_SHELL_CACHE.clear();
         PENDING_SHELL_FACES.clear();
         PENDING_SHELL_EDGES.clear();
      }
      if (confirmedLightweight && pendingLightweight) {
         return BuildingShellVisibility.NONE;
      }
      BlockGetter confirmedPreviewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(confirmedBlocks, state, stateOverrides);
      BlockGetter pendingPreviewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(pendingBlocks, state, stateOverrides);
      net.minecraft.world.phys.shapes.CollisionContext collision = net.minecraft.world.phys.shapes.CollisionContext.of(player);
      ShapeShellMesh.Mesh confirmed = confirmedLightweight ? ShapeShellMesh.Mesh.empty() : CONFIRMED_BUILDING_SHELL_CACHE.mesh(
         confirmedPreviewLevel, state, stateOverrides, collision, confirmedBlocks, confirmedBlocks, specialStyles, player.isShiftKeyDown()
      );
      ShapeShellMesh.Mesh pending = pendingLightweight ? ShapeShellMesh.Mesh.empty() : PENDING_BUILDING_SHELL_CACHE.mesh(
         pendingPreviewLevel, state, stateOverrides, collision, pendingBlocks, pendingBlocks, Map.of(), player.isShiftKeyDown()
      );

      float pendingFaceAlpha = 0.30F + 0.20F * breathPulse;
      renderBuildingFaces(
         poseStack,
         buffers,
         camera,
         confirmed.faces(),
         pending.faces(),
         previewAlpha(0.80F, worldPreviewOpacity),
         previewAlpha(pendingFaceAlpha, worldPreviewOpacity)
      );
      BuildingShellVisibility visibility = new BuildingShellVisibility(
         !confirmed.faces().isEmpty(), !pending.faces().isEmpty()
      );

      if (!cleanOutlineEdges) {
         buffers.endBatch(PENDING_XRAY_LINES);
         buffers.endBatch(GHOST_OUTLINE_LINES);
         CONFIRMED_SHELL_EDGES.draw(poseStack, camera, confirmed.edges(), PENDING_XRAY_LINES, 0.16F * worldPreviewOpacity);
         PENDING_SHELL_EDGES.draw(poseStack, camera, pending.edges(), PENDING_XRAY_LINES, 0.12F * worldPreviewOpacity);
         CONFIRMED_SHELL_EDGES.draw(poseStack, camera, confirmed.edges(), GHOST_OUTLINE_LINES, 0.92F * worldPreviewOpacity);
         PENDING_SHELL_EDGES.draw(poseStack, camera, pending.edges(), GHOST_OUTLINE_LINES, 0.82F * worldPreviewOpacity);
      } else {
         CONFIRMED_SHELL_EDGES.clear();
         PENDING_SHELL_EDGES.clear();
      }
      return visibility;
   }

   /**
    * Draws both shell layers with alphas that already include the global preview
    * fade. Both paths below use the same alphas, so the layer count cannot step
    * the preview opacity. See InteractionContext.previewVisibility.
    */
   private static void renderBuildingFaces(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      List<ShapeShellMesh.Face> confirmedFaces,
      List<ShapeShellMesh.Face> pendingFaces,
      float confirmedAlpha,
      float pendingAlpha
   ) {
      if (!confirmedFaces.isEmpty() && !pendingFaces.isEmpty()) {
         CONFIRMED_SHELL_FACES.clear();
         PENDING_SHELL_FACES.clear();
         ShapeShellRenderer.renderFaces(
            poseStack, buffers.getBuffer(GHOST_FACES), camera, confirmedFaces, confirmedAlpha
         );
         ShapeShellRenderer.renderFaces(
            poseStack, buffers.getBuffer(GHOST_FACES), camera, pendingFaces, pendingAlpha
         );
         buffers.endBatch(GHOST_FACES);
         return;
      }

      buffers.endBatch(GHOST_FACES);
      CONFIRMED_SHELL_FACES.draw(poseStack, camera, confirmedFaces, GHOST_FACES, confirmedAlpha);
      PENDING_SHELL_FACES.draw(poseStack, camera, pendingFaces, GHOST_FACES, pendingAlpha);
   }

   static float previewAlpha(float baseAlpha, float previewOpacity) {
      return Math.clamp(baseAlpha, 0.0F, 1.0F) * Math.clamp(previewOpacity, 0.0F, 1.0F);
   }

   record BuildingShellVisibility(boolean confirmedFacesVisible, boolean pendingFacesVisible) {
      static final BuildingShellVisibility NONE = new BuildingShellVisibility(false, false);
   }

}
