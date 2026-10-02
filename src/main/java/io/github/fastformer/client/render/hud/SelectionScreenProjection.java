package io.github.fastformer.client.render.hud;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Projects camera-relative boxes, clipping edges at the near plane before division. */
public record SelectionScreenProjection(Matrix4f matrix, Vec3 camera) {
   public record Rect(double left, double top, double right, double bottom) {
      public Rect union(Rect other) {
         return new Rect(Math.min(left, other.left), Math.min(top, other.top),
            Math.max(right, other.right), Math.max(bottom, other.bottom));
      }
   }
   public record Point(double x, double y, boolean onScreen) { }

   private Vector4f clip(Vec3 point) {
      Vec3 relative = point.subtract(camera);
      return matrix.transform(new Vector4f((float)relative.x, (float)relative.y, (float)relative.z, 1));
   }

   public Rect bounds(AABB box, int width, int height) {
      Vector4f[] corners = new Vector4f[8];
      List<Vector4f> visible = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
         corners[index] = clip(new Vec3((index & 1) == 0 ? box.minX : box.maxX,
            (index & 2) == 0 ? box.minY : box.maxY, (index & 4) == 0 ? box.minZ : box.maxZ));
         if (near(corners[index]) >= 0) visible.add(corners[index]);
      }
      for (int index = 0; index < 8; index++) for (int axis = 0; axis < 3; axis++) {
         int other = index ^ (1 << axis);
         if (other <= index) continue;
         float first = near(corners[index]), second = near(corners[other]);
         if ((first >= 0) != (second >= 0)) visible.add(new Vector4f(corners[index]).lerp(corners[other], first / (first - second)));
      }
      double left = Double.POSITIVE_INFINITY, top = left, right = -left, bottom = -left;
      for (Vector4f value : visible) {
         if (value.w <= 0.00001F) continue;
         double x = (value.x / value.w + 1) * width * 0.5;
         double y = (1 - value.y / value.w) * height * 0.5;
         left = Math.min(left, x); right = Math.max(right, x);
         top = Math.min(top, y); bottom = Math.max(bottom, y);
      }
      if (right < 0 || bottom < 0 || left > width || top > height || !Double.isFinite(left)) return null;
      return new Rect(Math.clamp(left - 5, 6, width - 6), Math.clamp(top - 5, 6, height - 6),
         Math.clamp(right + 5, 6, width - 6), Math.clamp(bottom + 5, 6, height - 6));
   }

   public Point point(Vec3 point, int width, int height) {
      Vector4f clip = clip(point);
      double divisor = Math.max(0.00001, Math.abs(clip.w));
      double x = clip.x / divisor, y = -clip.y / divisor;
      boolean inside = near(clip) >= 0 && clip.w > 0 && Math.abs(x) <= 0.9 && Math.abs(y) <= 0.85;
      if (!inside) {
         if (Math.abs(x) + Math.abs(y) < 0.0001) y = 1;
         double scale = Math.max(Math.abs(x) / 0.9, Math.abs(y) / 0.85);
         x /= scale; y /= scale;
      }
      return new Point((x + 1) * width * 0.5, (y + 1) * height * 0.5, inside);
   }
   private static float near(Vector4f value) { return value.z + value.w; }
}
