package io.github.fastformer.client.preview.frame;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Objects;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** World drawing inputs captured by the rendering boundary for one invocation. */
public record WorldPreviewFrame(PoseStack pose, BufferSource buffers, Vec3 camera, float opacity,
   AnimationPhase animation, long paletteRevision, Matrix4f modelView, Matrix4f projection) {
   public WorldPreviewFrame {
      Objects.requireNonNull(pose, "pose");
      Objects.requireNonNull(buffers, "buffers");
      Objects.requireNonNull(camera, "camera");
      Objects.requireNonNull(animation, "animation");
      modelView = new Matrix4f(Objects.requireNonNull(modelView, "modelView"));
      projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
      if (!Float.isFinite(opacity) || opacity < 0 || opacity > 1) throw new IllegalArgumentException("Invalid preview opacity");
   }
}
