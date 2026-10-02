package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ShellBufferTransformTest {
   @Test
   void cachedFacesAndEdgesMatchImmediateVerticesWithARotatedCamera() {
      Matrix4f view = new Matrix4f().rotateX(0.6F).rotateY(-1.1F);
      Matrix4f pose = new Matrix4f().translate(0.2F, -0.1F, 0).rotateZ(0.1F);
      Matrix4f originalView = new Matrix4f(view), originalPose = new Matrix4f(pose);
      Vec3 camera = new Vec3(105, 71, -209), origin = new Vec3(110, 65, -220);
      Vector3f local = new Vector3f(2, 1, 3);
      Vector3f immediate = new Vector3f(local).add((float)(origin.x - camera.x),
         (float)(origin.y - camera.y), (float)(origin.z - camera.z));
      pose.transformPosition(immediate);
      view.transformPosition(immediate);
      Vector3f cached = ShellBufferTransform.modelView(view, pose, origin, camera).transformPosition(new Vector3f(local));
      assertEquals(immediate.x, cached.x, 0.00001F);
      assertEquals(immediate.y, cached.y, 0.00001F);
      assertEquals(immediate.z, cached.z, 0.00001F);
      assertEquals(originalView, view);
      assertEquals(originalPose, pose);
   }
}
