package io.github.fastformer.client.render.cache;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Gives cached vertices the same camera transform as the immediate buffer path. */
final class ShellBufferTransform {
   private ShellBufferTransform() { }

   static Matrix4f modelView(Matrix4f view, Matrix4f pose, Vec3 origin, Vec3 camera) {
      return new Matrix4f(view).mul(pose).translate(
         (float)(origin.x - camera.x), (float)(origin.y - camera.y), (float)(origin.z - camera.z)
      );
   }
}
