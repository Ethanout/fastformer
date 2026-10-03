package io.github.fastformer.client.render.geometry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.client.render.theme.VisualThemes;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.client.render.style.DrawVisualStrokeEvent;
import io.github.fastformer.client.render.style.VisualStyleContext;

/** A stable pencil stroke. Only an explicitly dynamic stroke advances its seed. */
public final class PencilStroke {
   private PencilStroke() { }

   public static void draw(PoseStack pose, VertexConsumer vertices, Vec3 from, Vec3 to,
      float red, float green, float blue, float alpha, boolean dynamic) {
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 1.0E-7) return;
      var event = new DrawVisualStrokeEvent(VisualStyleContext.capture(VisualStyleContext.Pass.STROKE, dynamic),
         pose, vertices, from, to, new DrawVisualStrokeEvent.Color(red, green, blue, alpha));
      net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
      if (event.isCanceled()) return;
      Vec3 direction = delta.scale(1 / length);
      StrokeDashCoordinates dash = StrokeDashCoordinates.forLine(from, to);
      if (!GeometryPalette.humanist()) {
         segment(pose, vertices, from, to, direction, red, green, blue, alpha, dash.at(0), dash.at(1));
         return;
      }
      int sheet = BoilClock.sheet(dynamic);
      double fromScale = worldPerPixel(pose, from);
      double toScale = worldPerPixel(pose, to);
      double fromJitterScale = fromScale * jitterMultiplier(pose, from);
      double toJitterScale = toScale * jitterMultiplier(pose, to);
      double jitter = VisualThemes.value("boil_jitter_px", 0.1875F);
      Vec3 shiftedFrom = from.add(BoilJitter.cornerOffset(from, sheet, fromJitterScale, jitter));
      Vec3 shiftedTo = to.add(BoilJitter.cornerOffset(to, sheet, toJitterScale, jitter));
      direction = shiftedTo.subtract(shiftedFrom).normalize();
      if (direction.lengthSqr() < 1.0E-14) return;
      // Hash the original endpoints so tiny offsets cannot swap the preferred long end.
      PencilStrokeEnds ends = PencilStrokeEnds.forLine(from, to,
         VisualThemes.value("pencil_long_min", 0.15F), VisualThemes.value("pencil_long_max", 0.35F),
         VisualThemes.value("pencil_short_min", 0.02F), VisualThemes.value("pencil_short_max", 0.15F),
         sheet, VisualThemes.value("boil_overshoot_variation", 0.08F)
            * (jitterMultiplier(pose, from) + jitterMultiplier(pose, to)) * 0.5);
      Vec3 start = shiftedFrom.subtract(direction.scale(ends.start()));
      Vec3 end = shiftedTo.add(direction.scale(ends.end()));
      Vec3 side = direction.cross(Math.abs(direction.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
      // Canonicalize the bow direction when the same edge is visited in reverse.
      double dominant = Math.abs(side.x) >= Math.abs(side.y) && Math.abs(side.x) >= Math.abs(side.z) ? side.x
         : Math.abs(side.y) >= Math.abs(side.z) ? side.y : side.z;
      if (dominant < 0) side = side.scale(-1);
      // Static marks use a fixed hash sheet for a small permanent pencil bow;
      // animated marks use their current sheet so the bow can breathe.
      double bow = VisualThemes.value("boil_bow_px", 0.08F) * (sheet == 0 ? fromScale + toScale : fromJitterScale + toJitterScale) * 0.5
         * (sheet == 0 ? BoilJitter.staticBow(from, to) : BoilJitter.bow(from, to, sheet));
      segment(pose, vertices, start, shiftedFrom, direction, red, green, blue, alpha,
         dash.at(-ends.start() / length), dash.at(0));
      int count = bow == 0 ? 1 : 4;
      Vec3 previous = shiftedFrom;
      for (int i = 1; i <= count; i++) {
         double t = (double)i / count;
         Vec3 next = shiftedFrom.lerp(shiftedTo, t).add(side.scale(4 * t * (1 - t) * bow));
         segment(pose, vertices, previous, next, next.subtract(previous).normalize(), red, green, blue, alpha,
            dash.at((double)(i - 1) / count), dash.at(t));
         previous = next;
      }
      segment(pose, vertices, shiftedTo, end, direction, red, green, blue, alpha,
         dash.at(1), dash.at(1 + ends.end() / length));
   }

   private static double jitterMultiplier(PoseStack pose, Vec3 point) {
      var transform = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
      var view = transform.transformPosition((float)point.x, (float)point.y, (float)point.z, new org.joml.Vector3f());
      return VisualThemes.curve("jitter_by_distance").at(view.length());
   }

   private static double worldPerPixel(PoseStack pose, Vec3 point) {
      var transform = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
      var view = transform.transformPosition((float)point.x, (float)point.y, (float)point.z, new org.joml.Vector3f());
      double projectionScale = Math.abs(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix().m11());
      double localScale = transform.getScale(new org.joml.Vector3f()).length() / Math.sqrt(3);
      int height = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
      // Projection m11 includes the current FOV. Use GUI pixels like line widths.
      return 2 * Math.abs(view.z) * VisualStyleContext.guiScaleFactor()
         / Math.max(1.0E-6, projectionScale * Math.max(1, height) * localScale);
   }

   private static void segment(PoseStack pose, VertexConsumer vertices, Vec3 from, Vec3 to, Vec3 normal,
      float red, float green, float blue, float alpha, double fromAlong, double toAlong) {
      if (from.distanceToSqr(to) < 1.0E-14) return;
      vertices.addVertex(pose.last(), (float)from.x, (float)from.y, (float)from.z)
         .setColor(red, green, blue, alpha).setUv((float)fromAlong, 1F)
         .setNormal(pose.last(), (float)normal.x, (float)normal.y, (float)normal.z);
      vertices.addVertex(pose.last(), (float)to.x, (float)to.y, (float)to.z)
         .setColor(red, green, blue, alpha).setUv((float)toAlong, 1F)
         .setNormal(pose.last(), (float)normal.x, (float)normal.y, (float)normal.z);
   }
}
