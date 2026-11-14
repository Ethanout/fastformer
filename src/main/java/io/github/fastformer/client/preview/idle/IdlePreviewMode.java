package io.github.fastformer.client.preview.idle;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import io.github.fastformer.client.preview.frame.PreviewMode;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.PreviewMaterialRenderer;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.render.model.InitialBlockPreview;
import io.github.fastformer.client.render.model.PlacementModelPreview;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import java.util.Map;
import java.util.Set;

/** Owns the idle candidate model cache and draws only captured frame facts. */
public final class IdlePreviewMode implements PreviewMode<IdlePreviewInput, IdlePreviewModel> {
   private final PlacementModelPreview models = new PlacementModelPreview();

   @Override public IdlePreviewModel buildModel(IdlePreviewInput input) {
      if (input == null) return IdlePreviewModel.EMPTY;
      var preview = InitialBlockPreview.resolve(input.player(), input.hit(), input.embedded(), input.passesThroughFluids());
      return preview == null ? IdlePreviewModel.EMPTY : new IdlePreviewModel(preview, input.renderBlockModel());
   }

   @Override public void drawWorld(WorldPreviewFrame frame, IdlePreviewModel model) {
      if (model.block() == null) return;
      var preview = model.block();
      var pose = frame.pose();
      var buffers = frame.buffers();
      var camera = frame.camera();
      if (model.renderBlockModel()) {
         float alpha = PreviewMaterialRenderer.pendingAlpha(frame.animation().breathPulse(2_400_000_000L)) * frame.opacity();
         var fallback = models.render(pose, buffers, camera, Set.of(preview.position()), preview.state(), Map.of(), alpha, true);
         if (!fallback.isEmpty()) {
            ShapeShellRenderer.renderFaces(pose, buffers.getBuffer(GHOST_FACES), camera, preview.mesh().faces(), alpha);
            buffers.endBatch(GHOST_FACES);
         }
      }
      double offset = frame.animation().dashOffset(PreviewStyle.DASH_SPEED, PreviewStyle.DASH_PERIOD);
      ShapeShellRenderer.renderDashedEdges(pose, buffers.getBuffer(DYNAMIC_XRAY_LINES), camera, preview.mesh().edges(), frame.opacity(), offset);
      buffers.endBatch(DYNAMIC_XRAY_LINES);
      ShapeShellRenderer.renderDashedEdges(pose, buffers.getBuffer(DYNAMIC_LINES), camera, preview.mesh().edges(), frame.opacity(), offset);
      buffers.endBatch(DYNAMIC_LINES);
   }

   @Override public void clear() { models.clear(); }
}
