package io.github.fastformer.client.geometry.transform;

import io.github.fastformer.core.math.Axis;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGeometry;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/** Owns the baseline used by geometry gizmo capture and its HUD. */
public final class GeometryTransformValues {
   private final Supplier<GeometryPreviewPayload> preview;
   private final LongSupplier version;
   private long observedVersion = -1;
   private TransformStatus baseline;

   public GeometryTransformValues(Supplier<GeometryPreviewPayload> preview, LongSupplier version) {
      this.preview = Objects.requireNonNull(preview, "preview");
      this.version = Objects.requireNonNull(version, "version");
   }

   public double value(GeometryPreviewPlan plan, Axis axis, AxisGizmo.Operation operation) {
      if (axis == null || operation == null) return operation == AxisGizmo.Operation.SCALE ? 1 : 0;
      var transform = relative(plan);
      if (transform == null) return operation == AxisGizmo.Operation.SCALE ? 1 : 0;
      return switch (operation) {
         case MOVE -> transform.position().dot(plan == null || plan.gizmo() == null ? axisVector(axis) : plan.gizmo().axisVector(axis));
         case SCALE -> component(transform.scale(), axis);
         case ROTATE -> component(transform.rotationDegrees(), axis);
      };
   }

   public void clear() { baseline = null; observedVersion = -1; }

   private TransformStatus relative(GeometryPreviewPlan plan) {
      long currentVersion = version.getAsLong();
      if (observedVersion != currentVersion) { observedVersion = currentVersion; baseline = null; }
      var current = current(preview.get(), plan);
      if (current == null) return null;
      if (baseline == null) baseline = current;
      return current.relativeTo(baseline);
   }

   private static TransformStatus current(GeometryPreviewPayload p, GeometryPreviewPlan plan) {
      if (!p.active()) return null;
      if (p.mode() == GeometryMode.CONE_PRISM) {
         if (p.conePlaneMode().stageFor(p.points().size()) != io.github.fastformer.fastplace.geometry.cone.ConePrismStage.ADJUST) return null;
         int count = Math.min(p.pointLocations().size(), p.conePlaneMode().facePointCount());
         var geometry = ConePrismGeometry.from(new ConePrismParameters(List.copyOf(p.pointLocations().subList(0, count)),
            p.pointLocations().size() > count ? Optional.of(p.pointLocations().get(count)) : Optional.empty(), p.coneShapeVariant(),
            p.conePlaneMode(), p.coneRadius(), p.coneScaleX(), p.coneScaleZ(), p.coneTopScaleOffset(), p.coneTopOffset(), p.coneRotationRadians()));
         if (geometry == null || !geometry.heightReady()) return null;
         return new TransformStatus(geometry.base().center().add(geometry.topCenter()).scale(0.5),
            new Vec3(geometry.scaleX(), Math.max(0.5, Math.abs(geometry.height())), geometry.scaleZ()),
            new Vec3(0, Math.toDegrees(p.coneRotationRadians()), 0));
      }
      if (p.mode() != GeometryMode.POLYHEDRON || !p.closed()) return null;
      Vec3 center = plan != null && plan.gizmo() != null ? plan.gizmo().center()
         : p.pointLocations().isEmpty() ? null : p.pointLocations().getFirst();
      return center == null ? null : new TransformStatus(center, p.polyhedronGizmoLocal() ? p.polyhedronLocalScale() : p.polyhedronWorldScale(),
         TransformStatus.eulerDegrees(p.rotation()));
   }

   private static double component(Vec3 value, Axis axis) { return switch (axis) { case X -> value.x; case Y -> value.y; case Z -> value.z; }; }
   private static Vec3 axisVector(Axis axis) { return switch (axis) { case X -> new Vec3(1,0,0); case Y -> new Vec3(0,1,0); case Z -> new Vec3(0,0,1); }; }
}
