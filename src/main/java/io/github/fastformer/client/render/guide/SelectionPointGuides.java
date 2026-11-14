package io.github.fastformer.client.render.guide;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.common.geometry.model.GuideLine;
import io.github.fastformer.common.geometry.model.GuidePlane;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Builds and draws point-drag guides from facts captured by the selection preview. */
public record SelectionPointGuides(List<GuidePlane> planes, List<GuideLine> lines) {
   public SelectionPointGuides {
      planes = List.copyOf(planes);
      lines = List.copyOf(lines);
   }

   public static SelectionPointGuides capture(List<BlockPos> points, BlockPos target, Vec3 camera,
      Vec3 planeAnchor, Vec3 planeNormal, Vec3 lineAnchor, Vec3 lineDirection) {
      var planes = new ArrayList<GuidePlane>();
      var lines = new ArrayList<GuideLine>();
      if (planeAnchor != null && planeNormal != null) {
         var bounds = new ArrayList<>(points.stream().map(Vec3::atCenterOf).toList());
         if (target != null) bounds.add(Vec3.atCenterOf(target));
         planes.add(new GuidePlane(planeAnchor, planeNormal, bounds, true));
      }
      if (lineAnchor != null && lineDirection != null) {
         double radius = Math.max(8.0, camera.distanceTo(lineAnchor) * 0.3);
         if (target != null) radius = Math.max(radius,
            Math.abs(Vec3.atCenterOf(target).subtract(lineAnchor).dot(lineDirection)) + 4.0);
         lines.add(new GuideLine(lineAnchor.subtract(lineDirection.scale(radius)), lineAnchor.add(lineDirection.scale(radius))));
      }
      return new SelectionPointGuides(planes, lines);
   }

   public void draw(PoseStack pose, VertexConsumer vertices, Vec3 camera, float opacity) {
      if (planes.isEmpty() && lines.isEmpty()) return;
      GuideRenderer.renderGeometryPlanes(pose, vertices, camera, planes, opacity);
      GuideRenderer.renderGeometryLines(pose, vertices, camera, lines, opacity);
   }
}
