package io.github.fastformer.client.geometry.pointer;

import io.github.fastformer.client.geometry.transform.GeometryTransformValues;
import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.geometry.presentation.GeometryTextPresentation;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.pointer.PointRaycast;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.workflow.GeometryWorkflows;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/** One geometry owner's candidate, plan, targets and transform values. */
public final class GeometryPointer {
   private final Supplier<GeometryPreviewPayload> preview;
   private final LongSupplier version;
   private final Supplier<InteractionContext> context;
   private final Function<InteractionContext, LongRangeBlockRaycast.Result> raycast;
   private final Supplier<AxisGizmo.HandleKey> captured;
   private final GeometryPlanCache plans;
   private final GeometryTransformValues transforms;
   private final GeometryTextPresentation textPresentation = new GeometryTextPresentation();

   public GeometryPointer(Supplier<GeometryPreviewPayload> preview, LongSupplier version, LongSupplier transformVersion,
      Supplier<InteractionContext> context, Function<InteractionContext, LongRangeBlockRaycast.Result> raycast,
      Supplier<AxisGizmo.HandleKey> captured, BiConsumer<String, Throwable> failureLogger) {
      this.preview = Objects.requireNonNull(preview, "preview");
      this.version = Objects.requireNonNull(version, "version");
      this.context = Objects.requireNonNull(context, "context");
      this.raycast = Objects.requireNonNull(raycast, "raycast");
      this.captured = Objects.requireNonNull(captured, "captured");
      plans = new GeometryPlanCache(failureLogger);
      transforms = new GeometryTransformValues(preview, transformVersion);
   }

   public boolean awaitingFirstPoint() { var p = preview.get(); return p.active() && p.points().isEmpty(); }
   public boolean pointSelected() { var p = preview.get(); return p.active() && p.selectedPointIndex() >= 0; }

   public boolean allows(GeometryAction action) {
      var p = preview.get();
      var c = context.get();
      return p.active() && GeometryWorkflows.allows(GeometryWorkflowSnapshot.view(p, c != null && c.alternative(), new Vec3(0, 0, 1)), action);
   }

   public GeometryInteractionHit interactionHit() {
      var c = context.get();
      var p = preview.get();
      if (!available(p, c)) return null;
      var targets = GeometryWorkflows.get(p.mode()).interactionTargets(GeometryWorkflowSnapshot.view(p, c.alternative(), c.view()));
      return GeometryInteractionHit.nearest(c.eye(), c.view(), visibleReach(c, raycast.apply(c)), targets);
   }

   public GeometryHit candidateHit() {
      var c = context.get();
      var p = preview.get();
      return available(p, c) ? candidate(p, c, raycast.apply(c)) : null;
   }

   public BlockPos pointUnderCrosshair() {
      var c = context.get();
      var p = preview.get();
      return c == null || !p.active() ? null : hovered(p, c, raycast.apply(c));
   }

   public GeometryPreviewPlan plan() {
      var c = context.get();
      var p = preview.get();
      if (c == null) return GeometryPreviewPlan.controlPoints(p.points(), null);
      var ray = raycast.apply(c);
      var workflow = GeometryWorkflowSnapshot.view(p, c.alternative(), c.view());
      var candidate = available(p, c) && GeometryWorkflows.allows(workflow, GeometryAction.CANDIDATE_INPUT) ? candidate(p, c, ray) : null;
      return plans.build(new GeometryPlanInput(p, version.getAsLong(), c.alternative(), c.eye(), c.view(), candidate, hovered(p, c, ray)));
   }

   public AxisGizmo gizmo() {
      var c = context.get();
      return available(preview.get(), c) ? present(plan().gizmo(), c, raycast.apply(c)) : null;
   }

   /** Drawing keeps the scaled handle even when vanilla temporarily owns input. */
   public GeometryPreviewPlan presentedPlan() {
      var plan = textPresentation.present(plan());
      var c = context.get();
      return c == null ? plan : plan.withGizmo(present(plan.gizmo(), c, raycast.apply(c)));
   }

   public AxisGizmo.Hit gizmoHit() {
      var c = context.get();
      if (!available(preview.get(), c)) return null;
      var gizmo = gizmo();
      return gizmo == null ? null : gizmo.hitTest(c.eye(), c.view(), visibleReach(c, raycast.apply(c)));
   }

   public double gizmoValue(Axis axis, AxisGizmo.Operation operation) {
      if (context.get() == null || !preview.get().active()) return operation == AxisGizmo.Operation.SCALE ? 1 : 0;
      return transforms.value(plan(), axis, operation);
   }

   public double gizmoValue(GeometryPreviewPlan plan, Axis axis, AxisGizmo.Operation operation) {
      return transforms.value(plan, axis, operation);
   }

   public void clear() { plans.clear(); transforms.clear(); textPresentation.clear(); }
   public void invalidatePlan() { plans.clear(); textPresentation.clear(); }

   private AxisGizmo present(AxisGizmo gizmo, InteractionContext c, LongRangeBlockRaycast.Result ray) {
      if (gizmo == null) return null;
      var scale = GizmoViewScale.fromDistance(c.camera().distanceTo(gizmo.center()));
      gizmo = new AxisGizmo(gizmo.center(), scale.axisLength(), scale.handleRadius(), gizmo.frame(), gizmo.handles(), gizmo.textComponent());
      if (c.vanillaOwnsPointer()) return gizmo;
      var hit = gizmo.hitTest(c.eye(), c.view(), visibleReach(c, ray));
      return gizmo.withState(hit == null ? null : hit.handle().key(), captured.get());
   }

   private static GeometryHit candidate(GeometryPreviewPayload p, InteractionContext c, LongRangeBlockRaycast.Result ray) {
      if (ray.hit().getType() == Type.BLOCK) return GeometryHit.from(ray.hit());
      return GeometryHit.point(p.points().isEmpty() ? BlockPos.containing(c.eye().add(c.view().scale(8))) : p.points().getLast());
   }

   private static BlockPos hovered(GeometryPreviewPayload p, InteractionContext c, LongRangeBlockRaycast.Result ray) {
      if (p.mode() != GeometryMode.WALL || p.closed() || p.points().isEmpty()) return null;
      double reach = LongRangeBlockRaycast.MAX_REACH;
      if (ray.hit().getType() == Type.BLOCK) reach = Math.min(reach, c.eye().distanceTo(ray.hit().getLocation()) + 1.0E-4);
      return PointRaycast.nearest(List.of(p.points().getFirst()), c.eye(), c.view(), reach);
   }

   private static boolean available(GeometryPreviewPayload p, InteractionContext c) {
      return p.active() && c != null && !c.vanillaOwnsPointer();
   }

   private static double visibleReach(InteractionContext c, LongRangeBlockRaycast.Result ray) {
      return ray.hit().getType() == Type.BLOCK ? GeometryRayVisibility.visibleReach(ray.distance(), c.eye(), ray.hit()) : ray.distance();
   }
}
