package io.github.fastformer.client.render.shell;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.fastplace.geometry.GuideLine;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Draws the client-generated interaction-shape shell and its styled edges. */
public final class ShapeShellRenderer {
   private static final double FACE_OFFSET = 0.002;

   private ShapeShellRenderer() {
   }

   public static void renderFaces(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<ShapeShellMesh.Face> faces,
      float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (ShapeShellMesh.Face face : faces) {
         Vec3 normal = Vec3.atLowerCornerOf(face.direction().getNormal()).scale(FACE_OFFSET);
         List<Vec3> vertices = face.vertices();
         ShapeShellMesh.Color color = face.color();
         FastPlaceClientPreview.addGhostQuad(
            poseStack,
            consumer,
            vertices.get(0).add(normal),
            vertices.get(1).add(normal),
            vertices.get(2).add(normal),
            vertices.get(3).add(normal),
            color.red(),
            color.green(),
            color.blue(),
            alpha
         );
      }
      poseStack.popPose();
   }

   public static void renderEdges(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<ShapeShellMesh.StyledEdge> edges,
      float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (ShapeShellMesh.StyledEdge edge : edges) {
         ShapeShellMesh.Color color = edge.color();
         FastPlaceClientPreview.renderLine(
            poseStack,
            consumer,
            edge.from(),
            edge.to(),
            color.red(),
            color.green(),
            color.blue(),
            alpha
         );
      }
      poseStack.popPose();
   }

   /** Candidate contours use low alpha and the current drawing sheet. */
   public static void renderDashedEdges(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<ShapeShellMesh.StyledEdge> edges,
      float alpha,
      double offset
   ) {
      renderDynamicEdges(poseStack, consumer, camera, edges,
         alpha * io.github.fastformer.client.render.theme.VisualThemes.value("candidate_alpha", 0.8F));
   }

   public static void renderDynamicEdges(PoseStack poseStack, VertexConsumer consumer, Vec3 camera,
      List<ShapeShellMesh.StyledEdge> edges, float alpha) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (ShapeShellMesh.StyledEdge edge : edges) {
         var ink = edge.color();
         io.github.fastformer.client.render.geometry.PencilStroke.draw(poseStack, consumer,
            edge.from(), edge.to(), ink.red(), ink.green(), ink.blue(), alpha, true);
      }
      poseStack.popPose();
   }

   public static void renderOutlineEdges(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuideLine> confirmed,
      List<GuideLine> pending
   ) {
      var ink = io.github.fastformer.fastplace.geometry.GeometryPalette.ink();
      float alpha = io.github.fastformer.client.render.PreviewStyle.OUTLINE_ALPHA;
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GuideLine edge : confirmed) {
         FastPlaceClientPreview.renderLine(
            poseStack, consumer, edge.from(), edge.to(), ink.red(), ink.green(), ink.blue(), alpha
         );
      }
      // Candidate contours use lower alpha while confirmed contours remain still.
      for (GuideLine edge : pending) {
         io.github.fastformer.client.render.guide.GuideRenderer.renderAlternatingDashedLine(
            poseStack, consumer, edge.from(), edge.to(), alpha, 0.0,
            io.github.fastformer.client.render.PreviewStyle.DASH_LENGTH, 1.0F
         );
      }
      poseStack.popPose();
   }
}
