package io.github.fastformer.client.render.core;

import static io.github.fastformer.client.render.core.PreviewRenderResources.*;
import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.render.PreviewMaterialRenderer;
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
      boolean pendingLightweight
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

      Set<BlockPos> confirmedFallback = CONFIRMED_MODELS.render(poseStack, buffers, camera,
         confirmedLightweight ? Set.of() : confirmedBlocks, state, stateOverrides,
         previewAlpha(PreviewMaterialRenderer.confirmedAlpha(), worldPreviewOpacity), false);
      Set<BlockPos> pendingFallback = PENDING_MODELS.render(poseStack, buffers, camera,
         pendingLightweight ? Set.of() : pendingBlocks, state, stateOverrides,
         previewAlpha(PreviewMaterialRenderer.pendingAlpha(breathPulse), worldPreviewOpacity), true);
      var fallbackConfirmed = CONFIRMED_FALLBACK_SHELL.mesh(player.level(), null, Map.of(), collision,
         confirmedFallback, confirmedFallback, Map.of(), false);
      var fallbackPending = PENDING_FALLBACK_SHELL.mesh(player.level(), null, Map.of(), collision,
         pendingFallback, pendingFallback, Map.of(), false);
      renderBuildingFaces(
         poseStack,
         buffers,
         camera,
         fallbackConfirmed.faces(),
         fallbackPending.faces(),
         previewAlpha(PreviewStyle.FACE_ALPHA, worldPreviewOpacity),
         previewAlpha(PreviewMaterialRenderer.pendingAlpha(breathPulse), worldPreviewOpacity)
      );
      // Visibility means "the shell mesh exists", so the endpoint fallback stays out of the way.
      BuildingShellVisibility visibility = new BuildingShellVisibility(
         !confirmed.faces().isEmpty(), !pending.faces().isEmpty()
      );

      // Both stages keep a continuous contour. Only candidates change over time.
      buffers.endBatch(PENDING_XRAY_LINES);
      buffers.endBatch(GHOST_OUTLINE_LINES);
      PENDING_SHELL_EDGES.clear();
      CONFIRMED_SHELL_EDGES.draw(poseStack, camera, confirmed.edges(), PENDING_XRAY_LINES, PreviewStyle.OUTLINE_ALPHA * worldPreviewOpacity);
      CONFIRMED_SHELL_EDGES.draw(poseStack, camera, confirmed.edges(), GHOST_OUTLINE_LINES, PreviewStyle.OUTLINE_ALPHA * worldPreviewOpacity);
      ShapeShellRenderer.renderDashedEdges(poseStack, buffers.getBuffer(DYNAMIC_XRAY_LINES), camera,
         pending.edges(), worldPreviewOpacity, 0);
      buffers.endBatch(DYNAMIC_XRAY_LINES);
      ShapeShellRenderer.renderDashedEdges(poseStack, buffers.getBuffer(DYNAMIC_LINES), camera,
         pending.edges(), worldPreviewOpacity, 0);
      buffers.endBatch(DYNAMIC_LINES);
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
