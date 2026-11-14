package io.github.fastformer.client.operation.pointer;

import io.github.fastformer.common.geometry.gizmo.GizmoTextComponent;

import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.gizmo.OperationGizmoPresentation;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.pointer.PointRaycast;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.selection.geometry.SelectionPrism;
import io.github.fastformer.common.geometry.model.TransformFrame;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/** One operation owner's remote-selection queries shared by input and drawing. */
public final class RemoteSelectionPointer {
   private final Supplier<OperationPreviewPayload> preview;
   private final BooleanSupplier workspaceActive;
   private final Function<InteractionContext, LongRangeBlockRaycast.Result> raycast;
   private final Supplier<InteractionContext> context;
   private final Supplier<AxisGizmo.HandleKey> captured;

   public RemoteSelectionPointer(Supplier<OperationPreviewPayload> preview, BooleanSupplier workspaceActive,
      Function<InteractionContext, LongRangeBlockRaycast.Result> raycast,
      Supplier<InteractionContext> context, Supplier<AxisGizmo.HandleKey> captured) {
      this.preview = Objects.requireNonNull(preview, "preview");
      this.workspaceActive = Objects.requireNonNull(workspaceActive, "workspaceActive");
      this.raycast = Objects.requireNonNull(raycast, "raycast");
      this.context = Objects.requireNonNull(context, "context");
      this.captured = Objects.requireNonNull(captured, "captured");
   }

   public boolean operationNeedsFirst() { var p = preview.get(); return p.active() && !p.hasFirst(); }
   public boolean operationNeedsSecond() { var p = preview.get(); return p.active() && !p.hasSecond(); }
   public int operationPointCount() { var p = preview.get(); return p.active() ? p.points().size() : 0; }
   public boolean operationPrismBaseOpen() { var p = preview.get(); return p.active() && p.operationSelectionMode() == OperationSelectionMode.PRISM && p.operationPrismBasePointCount() == 0; }
   public boolean operationPointSelected() { return operationSelectedPointIndex() >= 0; }
   public int operationSelectedPointIndex() {
      var p = preview.get();
      int index = p.operationSelectedPointIndex();
      return p.active() && index >= 0 && index < p.points().size() ? index : -1;
   }
   public Vec3 operationPointCenter(int index) {
      var p = preview.get();
      return p.active() && index >= 0 && index < p.points().size() ? Vec3.atCenterOf(p.points().get(index)) : null;
   }

   public int operationPointUnderCrosshairIndex() {
      var context = this.context.get();
      var p = preview.get();
      if (context == null || !p.active() || p.operationSelectionConfirmed() || p.points().isEmpty()) return -1;
      var point = PointRaycast.nearest(p.points(), context.eye(), context.view(), LongRangeBlockRaycast.MAX_REACH);
      return point == null ? -1 : p.points().indexOf(point);
   }

   public SelectionPrism.EdgeInsertion operationPrismEdgeInsertion() {
      var context = this.context.get();
      var p = preview.get();
      if (context == null || !prism(p) || p.operationSelectionConfirmed() || context.alternative() || p.points().size() < 2 || operationPointUnderCrosshairIndex() >= 0) return null;
      return SelectionPrism.resolveEdgeInsertion(p.points(), p.operationPrismBasePointCount(), context.eye(), context.view(), sample(context).distance());
   }

   public SelectionPrism.GridPlane operationPointGridPlane(int index) {
      var p = preview.get();
      if (!prism(p) || index < 0 || index >= p.points().size()) return null;
      int base = p.operationPrismBasePointCount() >= 3 ? p.operationPrismBasePointCount() : p.points().size();
      return index < base && base >= 3 ? SelectionPrism.gridPlane(p.points().subList(0, base)) : null;
   }

   public SelectionPrism.GridLine operationPointGridLine(int index) {
      var p = preview.get();
      if (!prism(p) || index < 0 || index >= p.points().size()) return null;
      int closed = p.operationPrismBasePointCount();
      int base = closed >= 3 ? closed : p.points().size();
      if (base < 3 || index != 0 && (closed < 3 || index < base)) return null;
      return SelectionPrism.heightGridLine(p.points().subList(0, base));
   }

   public BlockPos operationCandidatePoint() {
      var context = this.context.get();
      var p = preview.get();
      if (context == null || p.active() && p.operationSelectionConfirmed()) return null;
      return SelectionCreationPointer.remoteCandidate(p, context, sample(context));
   }

   public OperationSelectionVolume operationSelection() {
      var p = preview.get();
      if (!p.active()) return null;
      if (p.operationSelectionMode() == OperationSelectionMode.CUBOID && p.selectionMin() != null && p.selectionMax() != null) {
         return OperationSelectionVolume.cuboid(p.selectionMin(), p.selectionMax(), p.points().isEmpty() ? null : p.points().getFirst(),
            p.points().size() < 2 ? null : p.points().get(1));
      }
      return OperationSelectionVolume.create(p.operationSelectionMode(), p.points(), p.operationPrismBasePointCount(),
         p.operationMinOffset(), p.operationMaxOffset(), p.operationHullInflation());
   }

   public OperationPointerTarget operationPointerTarget() {
      var context = this.context.get();
      if (context == null) return OperationPointerTarget.none();
      var ray = sample(context);
      var hit = ray.hit();
      OperationGeometry.RayHit face = null;
      var p = preview.get();
      if (p.active() && p.operationSelectionMode() == OperationSelectionMode.CUBOID && ready(p)) {
         var selection = operationSelection();
         if (selection != null) face = selection.raycast(context.eye(), context.view(), visibleReach(context, ray));
      }
      double distance = hit.getType() == Type.BLOCK ? context.eye().distanceTo(hit.getLocation()) : Double.POSITIVE_INFINITY;
      if (hit.getType() == Type.BLOCK && (face == null || distance < face.distance())) return OperationPointerTarget.world(hit.getBlockPos(), distance);
      return face == null ? OperationPointerTarget.none() : OperationPointerTarget.face(face);
   }

   public OperationGeometry.RayHit operationFaceHit() {
      var context = this.context.get();
      var p = preview.get();
      if (context == null || !p.active() || p.operationSelectionMode() != OperationSelectionMode.CUBOID || p.operationSelectionConfirmed() || context.alternative()) return null;
      return operationPointerTarget().face();
   }

   public AxisGizmo operationGizmo() {
      var context = this.context.get();
      var p = preview.get();
      if (context == null || workspaceActive.getAsBoolean() || !p.active() || !ready(p)) return null;
      var selection = operationSelection();
      if (selection == null) return null;
      var operations = OperationGizmoPresentation.operations(p.operationStageMode()).toArray(AxisGizmo.Operation[]::new);
      if (operations.length == 0) return null;
      var bounds = selection.bounds();
      var min = OperationGeometry.stackDisplacement(bounds, p.operationStackMin());
      var max = OperationGeometry.stackDisplacement(bounds, p.operationStackMax());
      Vec3 center = bounds.getCenter().add((min.getX()+max.getX())*0.5, (min.getY()+max.getY())*0.5, (min.getZ()+max.getZ())*0.5)
         .add(Vec3.atLowerCornerOf(p.operationTranslation()));
      var scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
      var gizmo = AxisGizmo.inFrame(TransformFrame.world(center), scale.axisLength(), scale.handleRadius(), operations)
         .withTextComponent(io.github.fastformer.common.geometry.gizmo.GizmoTextComponent.pointLevel());
      var hit = gizmo.hitTest(context.eye(), context.view(), LongRangeBlockRaycast.MAX_REACH);
      return gizmo.withState(hit == null ? null : hit.handle().key(), captured.get());
   }

   public double operationGizmoValue(Axis axis, AxisGizmo.Operation operation) {
      var p = preview.get();
      if (!p.active() || !ready(p) || axis == null || operation == null) return 0;
      Vec3 value = switch(operation) {
         case MOVE -> Vec3.atLowerCornerOf(p.operationTranslation());
         case SCALE -> Vec3.atLowerCornerOf(p.operationStackVector());
         case ROTATE -> p.operationRotation().scale(180 / Math.PI);
      };
      return switch (axis) { case X -> value.x; case Y -> value.y; case Z -> value.z; };
   }

   private LongRangeBlockRaycast.Result sample(InteractionContext context) { return raycast.apply(context); }
   private static double visibleReach(InteractionContext context, LongRangeBlockRaycast.Result ray) {
      return ray.hit().getType() == Type.BLOCK ? GeometryRayVisibility.visibleReach(ray.distance(), context.eye(), ray.hit()) : ray.distance();
   }
   private static boolean prism(OperationPreviewPayload p) { return p.active() && p.operationSelectionMode() == OperationSelectionMode.PRISM; }
   public static boolean ready(OperationPreviewPayload p) {
      var mode = p.operationSelectionMode();
      return mode == OperationSelectionMode.CUBOID ? p.hasFirst() && p.hasSecond()
         : mode == OperationSelectionMode.PRISM ? p.operationPrismBasePointCount() >= 3 && p.points().size() > p.operationPrismBasePointCount()
         : p.points().size() >= mode.requiredPoints();
   }
}
