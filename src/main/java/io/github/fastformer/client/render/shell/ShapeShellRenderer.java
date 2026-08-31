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

   public static void renderOutlineEdges(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuideLine> confirmed,
      List<GuideLine> pending
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GuideLine edge : confirmed) {
         FastPlaceClientPreview.renderLine(
            poseStack, consumer, edge.from(), edge.to(), 1.0F, 1.0F, 1.0F, 0.92F
         );
      }
      for (GuideLine edge : pending) {
         FastPlaceClientPreview.renderLine(
            poseStack, consumer, edge.from(), edge.to(), 1.0F, 1.0F, 1.0F, 0.82F
         );
      }
      poseStack.popPose();
   }
}
