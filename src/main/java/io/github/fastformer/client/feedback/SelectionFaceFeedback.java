package io.github.fastformer.client.feedback;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** Owns both face-guide transitions for one input environment, never advanced by drawing. */
public final class SelectionFaceFeedback {
   public record Face(Vec3 point, Vec3 normal, double distance, int axis) {}
   public record Frame(Face operation, Face workspace) {
      public static final Frame EMPTY = new Frame(null, null);
   }
   private final Smoother operation;
   private final Smoother workspace;
   private Frame frame = Frame.EMPTY;
   private Object scope;
   private long capturedFrame;
   private boolean captured;

   public SelectionFaceFeedback(long transitionNanos) {
      operation = new Smoother(transitionNanos);
      workspace = new Smoother(transitionNanos);
   }

   public Frame capture(long frameId, long now, Object scope, Face operationHit, Face workspaceHit, Object part) {
      if (!Objects.equals(this.scope, scope)) { clear(); this.scope = scope; }
      if (captured && capturedFrame == frameId) return frame;
      captured = true;
      capturedFrame = frameId;
      frame = new Frame(operation.update(operationHit, now, null), workspace.update(workspaceHit, now, part));
      return frame;
   }

   public Frame frame() { return frame; }
   public void clear() {
      operation.reset();
      workspace.reset();
      frame = Frame.EMPTY;
      scope = null;
      captured = false;
   }

   private static final class Smoother {
      private final LinearPositionSmoother position;
      private Face previous;
      private Object owner;
      Smoother(long duration) { position = new LinearPositionSmoother(duration); }
      Face update(Face target, long now, Object owner) {
         if (target == null) { reset(); return null; }
         Vec3 normal = target.normal().normalize();
         if (!Objects.equals(this.owner, owner) || previous != null
            && (previous.axis() != target.axis() || previous.normal().normalize().dot(normal) < 1 - 1.0E-7)) position.reset();
         this.owner = owner;
         previous = target;
         Vec3 smoothed = position.update(target.point(), now);
         Vec3 point = smoothed.add(normal.scale(target.point().subtract(smoothed).dot(normal)));
         return new Face(point, target.normal(), target.distance(), target.axis());
      }
      void reset() { position.reset(); previous = null; owner = null; }
   }
}
