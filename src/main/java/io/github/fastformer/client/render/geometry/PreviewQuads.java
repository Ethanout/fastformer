package io.github.fastformer.client.render.geometry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

public final class PreviewQuads {
   private PreviewQuads() {}

   public static void write(PoseStack poseStack, VertexConsumer consumer,
      Vec3 a, Vec3 b, Vec3 c, Vec3 d, float red, float green, float blue, float alpha) {
      Pose pose = poseStack.last();
      consumer.addVertex(pose, (float)a.x, (float)a.y, (float)a.z).setColor(red, green, blue, alpha);
      consumer.addVertex(pose, (float)b.x, (float)b.y, (float)b.z).setColor(red, green, blue, alpha);
      consumer.addVertex(pose, (float)c.x, (float)c.y, (float)c.z).setColor(red, green, blue, alpha);
      consumer.addVertex(pose, (float)d.x, (float)d.y, (float)d.z).setColor(red, green, blue, alpha);
   }
}
