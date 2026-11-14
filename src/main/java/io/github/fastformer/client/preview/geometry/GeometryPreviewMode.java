package io.github.fastformer.client.preview.geometry;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.PENDING_LINES;

import io.github.fastformer.client.controlpoint.ControlPointRenderer;
import io.github.fastformer.client.gizmo.GizmoRenderer;
import io.github.fastformer.client.preview.PreviewMode;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.render.guide.GuideRenderer;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

/** Owns geometry model generation, ghost resources, and world drawing. */
public final class GeometryPreviewMode implements PreviewMode<GeometryPreviewInput, GeometryPreviewModel> {
   private final GeometryPreviewModels models;
   private final GhostBlockPreviewRenderer blocks;

   public GeometryPreviewMode(BiConsumer<String, Throwable> failureLogger, ThreadPoolExecutor executor) {
      models = new GeometryPreviewModels();
      blocks = new GhostBlockPreviewRenderer(executor, failureLogger);
   }

   @Override public GeometryPreviewModel buildModel(GeometryPreviewInput input) { return models.build(input); }

   @Override public void clear() { models.clear(); blocks.clear(); }

   @Override public void drawWorld(WorldPreviewFrame frame, GeometryPreviewModel model) {
      blocks.draw(frame, model.blocks());
      var plan = model.plan();
      new ControlPointRenderer(frame.opacity(), frame.animation().dashOffset(PreviewStyle.DASH_SPEED, PreviewStyle.DASH_PERIOD),
         PreviewStyle.DASH_LENGTH).render(frame.pose(), frame.buffers(), frame.camera(), plan.controlPoints());
      GuideRenderer.renderGeometryPlanes(frame.pose(), frame.buffers().getBuffer(PENDING_LINES), frame.camera(), plan.guidePlanes(), frame.opacity());
      GuideRenderer.renderGeometryLines(frame.pose(), frame.buffers().getBuffer(PENDING_LINES), frame.camera(), plan.guideLines(), frame.opacity());
      frame.buffers().endBatch(PENDING_LINES);
      if (plan.gizmo() != null) new GizmoRenderer(frame.opacity()).renderGeometryGizmo(frame.pose(), frame.buffers(), frame.camera(), plan.gizmo());
   }
}
