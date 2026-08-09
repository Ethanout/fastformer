package io.github.fastformer.fastplace.geometry;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public record AxisGizmo(
   Vec3 center,
   double axisLength,
   double handleRadius,
   TransformFrame frame,
   List<Handle> handles,
   GizmoTextComponent textComponent
) {
   private static final double EPSILON = 1.0E-7;

   public AxisGizmo {
      center = center == null ? Vec3.ZERO : center;
      axisLength = Math.max(0.0, axisLength);
      handleRadius = Math.max(0.0, handleRadius);
      frame = frame == null ? TransformFrame.world(center) : frame;
      handles = handles == null ? defaultHandles() : List.copyOf(handles);
      textComponent = textComponent == null ? GizmoTextComponent.none() : textComponent;
   }

   public AxisGizmo(Vec3 center, double axisLength, double handleRadius) {
      this(center, axisLength, handleRadius, TransformFrame.world(center), defaultHandles(), GizmoTextComponent.none());
   }

   public AxisGizmo(Vec3 center, double axisLength, double handleRadius, TransformFrame frame, List<Handle> handles) {
      this(center, axisLength, handleRadius, frame, handles, GizmoTextComponent.none());
   }

   public static AxisGizmo world(Vec3 center, double axisLength, double handleRadius) {
      return new AxisGizmo(center, axisLength, handleRadius, TransformFrame.world(center), defaultHandles());
   }

   public static AxisGizmo inFrame(TransformFrame frame, double axisLength, double handleRadius) {
      return new AxisGizmo(frame.origin(), axisLength, handleRadius, frame, defaultHandles());
   }

   public static AxisGizmo inFrame(
      TransformFrame frame, double axisLength, double handleRadius, Operation... operations
   ) {
      return new AxisGizmo(frame.origin(), axisLength, handleRadius, frame, handlesFor(operations));
   }

   public static List<Handle> handlesFor(Operation... operations) {
      ArrayList<Handle> result = new ArrayList<>();
      if (operations == null) {
         return List.copyOf(result);
      }
      for (Axis axis : Axis.values()) {
         for (Operation operation : operations) {
            if (operation == null) {
               continue;
            }
            Direction direction = operation == Operation.ROTATE ? Direction.BIDIRECTIONAL : Direction.POSITIVE;
            if (operation == Operation.MOVE || operation == Operation.SCALE) {
               result.add(new Handle(operation, axis, Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE));
               result.add(new Handle(operation, axis, Direction.NEGATIVE, ControlPointRole.GIZMO_HANDLE));
            } else {
               result.add(new Handle(operation, axis, direction, ControlPointRole.GIZMO_HANDLE));
            }
         }
      }
      return List.copyOf(result);
   }

   public static List<Handle> defaultHandles() {
      ArrayList<Handle> result = new ArrayList<>(15);
      for (Axis axis : Axis.values()) {
         result.add(new Handle(Operation.MOVE, axis, Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE));
         result.add(new Handle(Operation.MOVE, axis, Direction.NEGATIVE, ControlPointRole.GIZMO_HANDLE));
         result.add(new Handle(Operation.SCALE, axis, Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE));
         result.add(new Handle(Operation.SCALE, axis, Direction.NEGATIVE, ControlPointRole.GIZMO_HANDLE));
         result.add(new Handle(Operation.ROTATE, axis, Direction.BIDIRECTIONAL, ControlPointRole.GIZMO_HANDLE));
      }
      return List.copyOf(result);
   }

   public Vec3 axisVector(Axis axis) {
      return this.frame.axis(axis);
   }

   public Vec3 handleCenter(Handle handle) {
      if (handle == null || handle.direction() == Direction.BIDIRECTIONAL) {
         return this.center;
      }
      double sign = handle.direction() == Direction.NEGATIVE ? -1.0 : 1.0;
      return this.center.add(this.axisVector(handle.axis()).scale(this.endpointDistance(handle) * sign));
   }

   public double visualRadius(Handle handle) {
      if (handle == null) {
         return this.handleRadius;
      }
      double operationMultiplier = handle.operation() == Operation.SCALE ? 0.8 : 1.0;
      return this.handleRadius * operationMultiplier;
   }

   public double endpointDistance(Handle handle) {
      if (handle == null) {
         return this.axisLength;
      }
      if (handle.operation() == Operation.MOVE) {
         return this.axisLength * 1.34;
      }
      if (handle.operation() == Operation.SCALE) {
         // Keep the repeat handle beyond the lengthened move arrow.
         return this.axisLength * 1.48;
      }
      return this.axisLength;
   }

   public double rotationRingRadius(Handle handle) {
      return this.axisLength * 0.75;
   }

   public AxisGizmo withHovered(HandleKey hoveredKey) {
      return this.withState(hoveredKey, null);
   }

   public AxisGizmo withState(HandleKey hoveredKey, HandleKey activeKey) {
      ArrayList<Handle> result = new ArrayList<>(this.handles.size());
      for (Handle handle : this.handles) {
         HandleKey key = handle.key();
         result.add(handle.withState(key.equals(hoveredKey), key.equals(activeKey)));
      }
      return new AxisGizmo(this.center, this.axisLength, this.handleRadius, this.frame, result, this.textComponent);
   }

   public AxisGizmo withHoverFeedback(HoverFeedback feedback) {
      ArrayList<Handle> result = new ArrayList<>(this.handles.size());
      for (Handle handle : this.handles) {
         result.add(handle.withHoverFeedback(feedback));
      }
      return new AxisGizmo(this.center, this.axisLength, this.handleRadius, this.frame, result, this.textComponent);
   }

   public AxisGizmo withPointGizmoHover() {
      ArrayList<Handle> result = new ArrayList<>(this.handles.size());
      for (Handle handle : this.handles) {
         result.add(handle.withHoverFeedback(HoverFeedback.pointGizmo(handle.hoverFeedback().normalColor())));
      }
      return new AxisGizmo(this.center, this.axisLength, this.handleRadius, this.frame, result, this.textComponent);
   }

   public AxisGizmo withTextComponent(GizmoTextComponent textComponent) {
      return new AxisGizmo(this.center, this.axisLength, this.handleRadius, this.frame, this.handles, textComponent);
   }

   public Hit hitTest(Vec3 eye, Vec3 view, double maxDistance) {
      Vec3 direction = normalize(view);
      if (eye == null || direction.lengthSqr() < EPSILON || maxDistance <= 0.0) {
         return null;
      }

      List<Hit> candidates = new ArrayList<>();
      for (Handle handle : this.handles) {
         if (handle.drawsEndpoint()) {
            Hit endpoint = this.hitEndpoint(handle, eye, direction, maxDistance);
            if (endpoint != null) {
               candidates.add(endpoint);
            }
            if (handle.operation() == Operation.MOVE) {
               Hit shaft = this.hitAxis(handle, eye, direction, maxDistance);
               if (shaft != null) {
                  candidates.add(shaft);
               }
            }
         } else if (handle.drawsRing()) {
            Hit ring = this.hitRing(handle, eye, direction, maxDistance);
            if (ring != null) {
               candidates.add(ring);
            }
         }
      }
      return preferHit(candidates);
   }

   /** Chooses an intentional control over a coincident decorative rotation ring. */
   public static Hit preferHit(List<Hit> candidates) {
      if (candidates == null || candidates.isEmpty()) {
         return null;
      }
      return candidates.stream().min((left, right) -> {
         int controlKind = Boolean.compare(left.handle().drawsRing(), right.handle().drawsRing());
         if (controlKind != 0) {
            return controlKind;
         }
         int handleDistance = Double.compare(left.handleDistance(), right.handleDistance());
         if (handleDistance != 0) {
            return handleDistance;
         }
         int operation = Integer.compare(hitPriority(left.handle()), hitPriority(right.handle()));
         return operation != 0 ? operation : Double.compare(left.rayDistance(), right.rayDistance());
      }).orElse(null);
   }

   private static int hitPriority(Handle handle) {
      return switch (handle.operation()) {
         case MOVE -> 0;
         case SCALE -> 1;
         case ROTATE -> 2;
      };
   }

   private Hit hitAxis(Handle handle, Vec3 eye, Vec3 direction, double maxDistance) {
      Vec3 axis = normalize(this.axisVector(handle.axis()));
      if (axis.lengthSqr() < EPSILON) {
         return null;
      }
      double sign = handle.direction() == Direction.NEGATIVE ? -1.0 : 1.0;
      Vec3 signedAxis = axis.scale(sign);
      Vec3 closestOnAxis = OperationGeometry.closestPointOnAxisToRay(this.center, signedAxis, eye, direction);
      double alongAxis = closestOnAxis.subtract(this.center).dot(signedAxis);
      double minimum = Math.max(this.handleRadius * 1.25, this.axisLength * 0.04);
      double maximum = Math.max(
         minimum,
         this.endpointDistance(handle) - this.visualRadius(handle) * 0.45
      );
      double clamped = Math.clamp(alongAxis, minimum, maximum);
      Vec3 point = this.center.add(signedAxis.scale(clamped));
      double rayDistance = point.subtract(eye).dot(direction);
      if (rayDistance < 0.0 || rayDistance > maxDistance) {
         return null;
      }
      Vec3 rayPoint = eye.add(direction.scale(rayDistance));
      double handleDistance = rayPoint.distanceTo(point);
      double tolerance = Math.max(this.handleRadius * 0.8, this.axisLength * 0.025);
      if (handleDistance > tolerance) {
         return null;
      }
      return new Hit(handle, point, rayDistance, handleDistance);
   }

   private Hit hitEndpoint(Handle handle, Vec3 eye, Vec3 direction, double maxDistance) {
      Vec3 handleCenter = this.handleCenter(handle);
      double radius = this.visualRadius(handle);
      Vec3 toHandle = handleCenter.subtract(eye);
      double alongRay = toHandle.dot(direction);
      if (alongRay < 0.0 || alongRay > maxDistance) {
         return null;
      }

      Vec3 closest = eye.add(direction.scale(alongRay));
      double distanceSqr = closest.distanceToSqr(handleCenter);
      double radiusSqr = radius * radius;
      if (distanceSqr > radiusSqr) {
         return null;
      }

      double entryDistance = Math.max(0.0, alongRay - Math.sqrt(Math.max(0.0, radiusSqr - distanceSqr)));
      if (entryDistance > maxDistance) {
         return null;
      }
      return new Hit(handle, eye.add(direction.scale(entryDistance)), entryDistance, Math.sqrt(distanceSqr));
   }

   private Hit hitRing(Handle handle, Vec3 eye, Vec3 direction, double maxDistance) {
      Vec3 normal = normalize(this.axisVector(handle.axis()));
      if (normal.lengthSqr() < EPSILON) {
         return null;
      }

      double denominator = normal.dot(direction);
      if (Math.abs(denominator) < EPSILON) {
         return null;
      }

      double rayDistance = this.center.subtract(eye).dot(normal) / denominator;
      if (rayDistance < 0.0 || rayDistance > maxDistance) {
         return null;
      }

      Vec3 point = eye.add(direction.scale(rayDistance));
      double radialDistance = point.subtract(this.center).length();
      double ringRadius = this.rotationRingRadius(handle);
      double tolerance = Math.max(this.handleRadius * 1.25, this.axisLength * 0.025);
      double handleDistance = Math.abs(radialDistance - ringRadius);
      if (handleDistance > tolerance) {
         return null;
      }
      return new Hit(handle, point, rayDistance, handleDistance);
   }

   private static Vec3 normalize(Vec3 vector) {
      if (vector == null || vector.lengthSqr() < EPSILON) {
         return Vec3.ZERO;
      }
      return vector.normalize();
   }

   public record Handle(
      Operation operation,
      Axis axis,
      Direction direction,
      ControlPointRole role,
      HoverFeedback hoverFeedback,
      boolean hovered,
      boolean active
   ) {
      public Handle {
         operation = operation == null ? Operation.MOVE : operation;
         axis = axis == null ? Axis.X : axis;
         direction = direction == null ? Direction.POSITIVE : direction;
         role = role == null ? ControlPointRole.GIZMO_HANDLE : role;
         hoverFeedback = hoverFeedback == null ? HoverFeedback.axis(axisColor(axis)) : hoverFeedback;
      }

      public Handle(Operation operation, Axis axis, Direction direction, ControlPointRole role) {
         this(operation, axis, direction, role, HoverFeedback.axis(axisColor(axis)), false, false);
      }

      public Handle(Operation operation, Axis axis, Direction direction, ControlPointRole role, boolean hovered, boolean active) {
         this(operation, axis, direction, role, HoverFeedback.axis(axisColor(axis)), hovered, active);
      }

      public HandleKey key() {
         return new HandleKey(this.operation, this.axis, this.direction);
      }

      public Handle withState(boolean hovered, boolean active) {
         return new Handle(this.operation, this.axis, this.direction, this.role, this.hoverFeedback, hovered, active);
      }

      public Handle withHoverFeedback(HoverFeedback feedback) {
         return new Handle(this.operation, this.axis, this.direction, this.role, feedback, this.hovered, this.active);
      }

      public boolean drawsEndpoint() {
         return this.direction != Direction.BIDIRECTIONAL && (this.operation == Operation.MOVE || this.operation == Operation.SCALE);
      }

      public boolean drawsRing() {
         return this.direction == Direction.BIDIRECTIONAL && this.operation == Operation.ROTATE;
      }
   }

   public static int axisColor(Axis axis) {
      return switch (axis) {
         case X -> 0xFF382E;
         case Y -> 0x40FF59;
         case Z -> 0x408CFF;
      };
   }

   public record Hit(Handle handle, Vec3 point, double rayDistance, double handleDistance) {
   }

   public record HandleKey(Operation operation, Axis axis, Direction direction) {
   }

   public enum Operation {
      MOVE,
      SCALE,
      ROTATE
   }

   public enum Axis {
      X,
      Y,
      Z
   }

   public enum Direction {
      POSITIVE,
      NEGATIVE,
      BIDIRECTIONAL
   }
}
