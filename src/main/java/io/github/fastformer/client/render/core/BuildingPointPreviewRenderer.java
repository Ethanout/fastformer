package io.github.fastformer.client.render.core;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.PreviewMaterialRenderer;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPoint;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Uses tinted placement models for every building point and shared controls as fallback. */
final class BuildingPointPreviewRenderer {
   private BuildingPointPreviewRenderer() { }

   static void render(PoseStack pose, BufferSource buffers, Vec3 camera, List<ControlPoint> points,
      BlockState state, Map<BlockPos, BlockState> overrides, float opacity) {
      var minecraft = Minecraft.getInstance();
      var fallback = new ArrayList<ControlPoint>();
      for (ControlPoint point : points) {
         BlockPos pos = BlockPos.containing(point.center());
         BlockState at = overrides.getOrDefault(pos, state);
         if (minecraft.level == null || minecraft.player == null
            || at == null || at.getRenderShape() != RenderShape.MODEL
            || minecraft.getBlockRenderer().getBlockModel(at) == minecraft.getModelManager().getMissingModel()
            || io.github.fastformer.client.render.FastPlaceClientShaders.previewMaterial() == null) {
            fallback.add(point);
            continue;
         }
         var style = point.feedback().style(point.role(), point.hovered());
         var level = PreviewBlockOcclusion.level(minecraft.level, Map.of(pos, at));
         PreviewMaterialRenderer.drawMarker(pose, buffers, minecraft, camera,
            Map.of(pos, new ClientBlockSnapshot(at, null)), level,
            style.red(), style.green(), style.blue(), style.alpha() * opacity, !point.confirmed());
         var boxes = at.getShape(level, pos, CollisionContext.of(minecraft.player)).toAabbs().stream()
            .map(box -> box.move(pos)).toList();
         var color = new ShapeShellMesh.Color(style.red(), style.green(), style.blue());
         var mesh = ShapeShellMesh.build(List.of(new ShapeShellMesh.Part(boxes, color, color, true)));
         var lines = point.confirmed() ? PreviewRenderTypes.GHOST_OUTLINE_LINES : PreviewRenderTypes.DYNAMIC_LINES;
         if (point.confirmed()) ShapeShellRenderer.renderEdges(pose, buffers.getBuffer(lines), camera, mesh.edges(), style.alpha());
         else ShapeShellRenderer.renderDynamicEdges(pose, buffers.getBuffer(lines), camera, mesh.edges(), style.alpha() * opacity);
         buffers.endBatch(lines);
      }
      FastPlaceClientPreviewCore.renderControlPoints(pose, buffers, camera, fallback);
   }
}
