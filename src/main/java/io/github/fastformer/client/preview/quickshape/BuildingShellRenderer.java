package io.github.fastformer.client.preview.quickshape;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.render.PreviewMaterialRenderer;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import io.github.fastformer.client.render.model.PlacementModelPreview;
import io.github.fastformer.client.render.cache.BuildingShellCache;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.cache.BuildingShellFaceBuffer;
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
   private final PlacementModelPreview confirmedModels = new PlacementModelPreview();
   private final PlacementModelPreview pendingModels = new PlacementModelPreview();
   private final BuildingShellCache confirmedShell = BuildingShellCache.forImmutableSnapshots(false);
   private final BuildingShellCache pendingShell = BuildingShellCache.forImmutableSnapshots(true);
   private final BuildingShellCache confirmedFallback = BuildingShellCache.forImmutableSnapshots(false);
   private final BuildingShellCache pendingFallback = BuildingShellCache.forImmutableSnapshots(true);
   private final BuildingShellEdgeBuffer confirmedEdges = new BuildingShellEdgeBuffer();
   private final BuildingShellFaceBuffer confirmedFaces = new BuildingShellFaceBuffer();
   private final BuildingShellFaceBuffer pendingFaces = new BuildingShellFaceBuffer();

   void clear() {
      confirmedModels.clear(); pendingModels.clear();
      confirmedShell.clear(); pendingShell.clear();
      confirmedFallback.clear(); pendingFallback.clear();
      confirmedEdges.clear(); confirmedFaces.clear(); pendingFaces.clear();
   }

   void render(
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
         confirmedShell.clear();
         confirmedFaces.clear();
         confirmedEdges.clear();
      }
      if (pendingLightweight) {
         pendingShell.clear();
         pendingFaces.clear();
      }
      if (confirmedLightweight && pendingLightweight) {
         return;
      }
      BlockGetter confirmedPreviewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(confirmedBlocks, state, stateOverrides);
      BlockGetter pendingPreviewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(pendingBlocks, state, stateOverrides);
      net.minecraft.world.phys.shapes.CollisionContext collision = net.minecraft.world.phys.shapes.CollisionContext.of(player);
      ShapeShellMesh.Mesh confirmed = confirmedLightweight ? ShapeShellMesh.Mesh.empty() : confirmedShell.mesh(
         confirmedPreviewLevel, state, stateOverrides, collision, confirmedBlocks, confirmedBlocks, specialStyles, player.isShiftKeyDown()
      );
      ShapeShellMesh.Mesh pending = pendingLightweight ? ShapeShellMesh.Mesh.empty() : pendingShell.mesh(
         pendingPreviewLevel, state, stateOverrides, collision, pendingBlocks, pendingBlocks, Map.of(), player.isShiftKeyDown()
      );

      Set<BlockPos> confirmedFallbackBlocks = confirmedModels.render(poseStack, buffers, camera,
         confirmedLightweight ? Set.of() : confirmedBlocks, state, stateOverrides,
         previewAlpha(PreviewMaterialRenderer.confirmedAlpha(), worldPreviewOpacity), false);
      Set<BlockPos> pendingFallbackBlocks = pendingModels.render(poseStack, buffers, camera,
         pendingLightweight ? Set.of() : pendingBlocks, state, stateOverrides,
         previewAlpha(PreviewMaterialRenderer.pendingAlpha(breathPulse), worldPreviewOpacity), true);
      var fallbackConfirmed = confirmedFallback.mesh(player.level(), null, Map.of(), collision,
         confirmedFallbackBlocks, confirmedFallbackBlocks, Map.of(), false);
      var fallbackPending = pendingFallback.mesh(player.level(), null, Map.of(), collision,
         pendingFallbackBlocks, pendingFallbackBlocks, Map.of(), false);
      renderBuildingFaces(
         poseStack,
         buffers,
         camera,
         fallbackConfirmed.faces(),
         fallbackPending.faces(),
         previewAlpha(PreviewStyle.FACE_ALPHA, worldPreviewOpacity),
         previewAlpha(PreviewMaterialRenderer.pendingAlpha(breathPulse), worldPreviewOpacity)
      );
      // Both stages keep a continuous contour. Only candidates change over time.
      buffers.endBatch(PENDING_XRAY_LINES);
      buffers.endBatch(GHOST_OUTLINE_LINES);
      confirmedEdges.draw(poseStack, camera, confirmed.edges(), PENDING_XRAY_LINES, PreviewStyle.OUTLINE_ALPHA * worldPreviewOpacity);
      confirmedEdges.draw(poseStack, camera, confirmed.edges(), GHOST_OUTLINE_LINES, PreviewStyle.OUTLINE_ALPHA * worldPreviewOpacity);
      ShapeShellRenderer.renderDashedEdges(poseStack, buffers.getBuffer(DYNAMIC_XRAY_LINES), camera,
         pending.edges(), worldPreviewOpacity, 0);
      buffers.endBatch(DYNAMIC_XRAY_LINES);
      ShapeShellRenderer.renderDashedEdges(poseStack, buffers.getBuffer(DYNAMIC_LINES), camera,
         pending.edges(), worldPreviewOpacity, 0);
      buffers.endBatch(DYNAMIC_LINES);
   }

   /**
    * Draws both shell layers with alphas that already include the global preview
    * fade. Both paths below use the same alphas, so the layer count cannot step
    * the preview opacity. See InteractionContext.previewVisibility.
    */
   private void renderBuildingFaces(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      List<ShapeShellMesh.Face> confirmedFaces,
      List<ShapeShellMesh.Face> pendingFaces,
      float confirmedAlpha,
      float pendingAlpha
   ) {
      if (!confirmedFaces.isEmpty() && !pendingFaces.isEmpty()) {
         this.confirmedFaces.clear();
         this.pendingFaces.clear();
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
      this.confirmedFaces.draw(poseStack, camera, confirmedFaces, GHOST_FACES, confirmedAlpha);
      this.pendingFaces.draw(poseStack, camera, pendingFaces, GHOST_FACES, pendingAlpha);
   }

   static float previewAlpha(float baseAlpha, float previewOpacity) {
      return Math.clamp(baseAlpha, 0.0F, 1.0F) * Math.clamp(previewOpacity, 0.0F, 1.0F);
   }

}
