package io.github.fastformer.client.render.geometry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.client.preview.style.GeometryPalette;
import io.github.fastformer.common.geometry.model.GuideLine;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Draws authoritative selection boundaries with explicit alpha and no session queries. */
public final class SelectionVolumeRenderer {
   private SelectionVolumeRenderer() {}

   public static void render(PoseStack pose, VertexConsumer lines, OperationSelectionVolume selection, Vec3 offset, float alpha) {
      if (selection.prism() != null) {
         for (GuideLine edge : selection.prism().move(offset).edges()) line(pose, lines, edge.from(), edge.to(), alpha);
      } else {
         box(pose, lines, selection.bounds().move(offset).inflate(OperationSelectionVolume.RAYCAST_INFLATE), alpha);
      }
   }

   public static void line(PoseStack pose, VertexConsumer lines, Vec3 from, Vec3 to, float alpha) {
      var ink = GeometryPalette.ink();
      PencilStroke.draw(pose, lines, from, to, ink.red(), ink.green(), ink.blue(), alpha, false);
   }

   private static void box(PoseStack pose, VertexConsumer lines, AABB box, float alpha) {
      Vec3 p000 = new Vec3(box.minX, box.minY, box.minZ);
      Vec3 p001 = new Vec3(box.minX, box.minY, box.maxZ);
      Vec3 p010 = new Vec3(box.minX, box.maxY, box.minZ);
      Vec3 p011 = new Vec3(box.minX, box.maxY, box.maxZ);
      Vec3 p100 = new Vec3(box.maxX, box.minY, box.minZ);
      Vec3 p101 = new Vec3(box.maxX, box.minY, box.maxZ);
      Vec3 p110 = new Vec3(box.maxX, box.maxY, box.minZ);
      Vec3 p111 = new Vec3(box.maxX, box.maxY, box.maxZ);
      line(pose, lines, p000, p001, alpha);
      line(pose, lines, p000, p010, alpha);
      line(pose, lines, p000, p100, alpha);
      line(pose, lines, p001, p011, alpha);
      line(pose, lines, p001, p101, alpha);
      line(pose, lines, p010, p011, alpha);
      line(pose, lines, p010, p110, alpha);
      line(pose, lines, p100, p101, alpha);
      line(pose, lines, p100, p110, alpha);
      line(pose, lines, p011, p111, alpha);
      line(pose, lines, p101, p111, alpha);
      line(pose, lines, p110, p111, alpha);
   }
}
