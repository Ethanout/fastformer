package io.github.fastformer.client.render.geometry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.client.render.GhostOutlineDepthBias;
import io.github.fastformer.client.render.guide.GuideRenderer;
import net.minecraft.world.phys.Vec3;

/** Shared dashed edges for candidate control points and selection outlines. */
public final class DashedBoxRenderer {
   private DashedBoxRenderer() {
   }

   public static void render(PoseStack poseStack, VertexConsumer consumer, Vec3 center, Vec3 halfExtents,
      Vec3 camera, double cameraBias, double offset, float alpha, double dashLength, float opacity) {
      double x0 = center.x - halfExtents.x;
      double y0 = center.y - halfExtents.y;
      double z0 = center.z - halfExtents.z;
      double x1 = center.x + halfExtents.x;
      double y1 = center.y + halfExtents.y;
      double z1 = center.z + halfExtents.z;
      Vec3[] corners = {
         new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0),
         new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)
      };
      if (camera != null) {
         for (int index = 0; index < corners.length; index++) {
            corners[index] = GhostOutlineDepthBias.towardCamera(corners[index], camera, cameraBias);
         }
      }
      int[][] edges = {
         {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
      };
      for (int[] edge : edges) {
         GuideRenderer.renderAlternatingDashedLine(
            poseStack, consumer, corners[edge[0]], corners[edge[1]], alpha, offset, dashLength, opacity
         );
      }
   }
}
