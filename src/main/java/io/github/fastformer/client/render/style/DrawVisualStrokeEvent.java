package io.github.fastformer.client.render.style;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/** Cancel after emitting replacement line vertices. Cached strokes invoke this only when rebuilt. */
public final class DrawVisualStrokeEvent extends Event implements ICancellableEvent {
   private final VisualStyleContext context;
   private final PoseStack pose;
   private final VertexConsumer vertices;
   private final Vec3 from;
   private final Vec3 to;
   private final Color color;

   public record Color(float red, float green, float blue, float alpha) { }

   public DrawVisualStrokeEvent(VisualStyleContext context, PoseStack pose, VertexConsumer vertices,
      Vec3 from, Vec3 to, Color color) {
      this.context = context;
      this.pose = pose;
      this.vertices = vertices;
      this.from = from;
      this.to = to;
      this.color = color;
   }

   public VisualStyleContext context() { return context; }
   public PoseStack pose() { return pose; }
   public VertexConsumer vertices() { return vertices; }
   public Vec3 from() { return from; }
   public Vec3 to() { return to; }
   public Color color() { return color; }
}
