package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryConstraints;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryPreviewGuides;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryStage;
import io.github.fastformer.fastplace.geometry.GeometryStageDisplay;
import io.github.fastformer.fastplace.geometry.GeometryTextBlock;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGeometry;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Optional;

public final class ConePrismWorkflow implements GeometryWorkflow {
   private static final double CENTERLINE_HIT_RADIUS = 0.75;
   private static final int DETAILED_PREVIEW_SCAN_LIMIT = 16000;
   private static final int FALLBACK_PREVIEW_BLOCK_LIMIT = 4096;

   @Override
   public GeometryMode mode() {
      return GeometryMode.CONE_PRISM;
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, BlockPos point) {
      this.onAddPoint(session, context, GeometryHit.point(point));
   }

   @Override
   public void onAddPoint(GeometrySession session, GeometryActionContext context, GeometryHit hit) {
      if (!this.allows(session, GeometryAction.POINT_INPUT)) {
         return;
      }
      if (session.coneStage() == ConePrismStage.BODY) {
         ConePrismParameters parameters = parameters(session);
         session.setConeShapeVariant(this.nearCenterline(parameters, context.eye(), context.view()) ? 1 : 0);
         session.addPoint(this.heightPoint(parameters, context.eye(), context.view(), hit.conePoint(context.modifierHeld())));
      } else if (session.conePlaneMode() == ConePlaneMode.RADIUS && session.points().isEmpty()) {
         session.addPoint(hit.conePoint(context.modifierHeld()));
      } else if (session.conePlaneMode() == ConePlaneMode.RADIUS && session.points().size() == 1) {
         Vec3 center = session.pointLocations().getFirst();
         Vec3 candidate = hit.conePoint(context.modifierHeld());
         session.setConeRadius(horizontalRadius(candidate.subtract(center)));
         session.addPoint(radiusPoint(center, candidate, session.coneRadius()));
      } else {
         session.addPoint(hit.conePoint(context.modifierHeld()));
      }
   }

   @Override
   public boolean onScroll(GeometrySession session, GeometryActionContext context, int steps) {
      if (!this.allows(session, GeometryAction.SCALAR_ADJUST)) {
         return false;
      }
      if (this.scrollsTopScale(parameters(session), context.view())) {
         session.adjustConeTopScale(steps);
      } else {
         ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters(session));
         if (base != null) {
            session.adjustConeTopOffset(context.view(), base.axisU(), base.axisV(), steps);
         }
      }
      return true;
   }

   @Override
   public boolean onCycleMode(GeometrySession session, GeometryActionContext context) {
      if (!this.allows(session, GeometryAction.MODE_CYCLE)) {
         return false;
      }
      if (session.coneStage() == ConePrismStage.ADJUST) {
         session.toggleConeGizmoFrame();
      } else {
         session.cycleConePlaneMode();
      }
      return true;
   }

   @Override
   public boolean onGizmoDrag(
      GeometrySession session,
      GeometryActionContext context,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int steps
   ) {
      if (steps == 0 || session.coneStage() != ConePrismStage.ADJUST) {
         return false;
      }
      ConePrismParameters parameters = parameters(session);
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (geometry == null || !geometry.heightReady()) {
         return false;
      }
      ConePrismGeometry.Base base = geometry.base();
      TransformFrame frame = coneFrame(geometry, session.coneGizmoLocal());
      Vec3 direction = frame.axis(axis);
      double normalComponent = direction.dot(base.normal());
      double uComponent = direction.dot(base.axisU());
      double vComponent = direction.dot(base.axisV());
      switch (operation) {
         case MOVE -> session.moveCone(direction.scale((double)steps * 0.5));
         case SCALE -> {
            if (Math.abs(normalComponent) >= Math.max(Math.abs(uComponent), Math.abs(vComponent))) {
               session.adjustConeHeight(base.center(), base.normal(), (double)steps * 0.5);
            } else if (Math.abs(uComponent) >= Math.abs(vComponent)) {
               session.adjustConeScale(AxisGizmo.Axis.X, steps, base.radius());
            } else {
               session.adjustConeScale(AxisGizmo.Axis.Z, steps, base.radius());
            }
         }
         case ROTATE -> {
            if (!session.coneGizmoLocal() || axis != AxisGizmo.Axis.Y) {
               return false;
            }
            session.rotateCone(steps, 1024);
         }
      }
      return true;
   }

   @Override
   public GeometryStage stage(GeometryWorkflowView view) {
      ConePrismStage stage = view.coneStage();
      return switch (stage) {
         case FACE -> GeometryStage.collecting("cone_prism.base_face")
            .allow(GeometryAction.MODE_CYCLE)
            .allow(GeometryAction.SUBMODE);
         case BODY -> GeometryStage.collecting("cone_prism.height").allow(GeometryAction.SUBMODE);
         case ADJUST -> GeometryStage.adjusting("cone_prism.adjust")
            .allow(GeometryAction.MODE_CYCLE)
            .allow(GeometryAction.SUBMODE)
            .allow(GeometryAction.GIZMO_DRAG);
      };
   }

   @Override
   public boolean canFill(GeometrySession session) {
      return session.coneBodyComplete();
   }

   @Override
   public GeometryBuildResult build(GeometrySession session, GeometryActionContext context, FillMode fillMode, int maxBlocks) {
      ConePrismParameters parameters = parameters(session);
      long scanCells = ConePrismGenerator.estimateScanCells(parameters);
      return GeometryBuildResult.ready(
         scanCells,
         () -> ConePrismGenerator.generate(parameters, fillMode, maxBlocks)
      );
   }

   @Override
   public Component blockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.fill_blocked_cone", this.missingPointHint(session));
   }

   @Override
   public Component pointBlockedMessage(GeometrySession session) {
      return Component.translatable("fastformer.geometry.message.cone_no_points_enter");
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, BlockPos candidatePoint, Vec3 eye
   ) {
      return this.previewPlan(view, points, hoveredPoint, candidatePoint == null ? null : GeometryHit.point(candidatePoint), eye);
   }

   @Override
   public GeometryPreviewPlan previewPlan(
      GeometryWorkflowView view, List<BlockPos> points, BlockPos hoveredPoint, GeometryHit candidateHit, Vec3 eye
   ) {
      ConePrismStage stage = view.coneStage();
      List<Vec3> previewLocations = this.previewLocations(view, candidateHit, eye);
      BlockPos previewCandidate = previewLocations.size() > view.pointLocations().size() ? BlockPos.containing(previewLocations.getLast()) : null;
      double previewRadius = this.previewRadius(view, previewLocations);
      MutableComponent hint = switch (stage) {
         case FACE -> Component.translatable("fastformer.message.cone_hint_face", this.nextPointHint(view.conePlaneMode(), view.pointCount()));
         case BODY -> Component.translatable("fastformer.message.cone_hint_body");
         case ADJUST -> Component.translatable("fastformer.message.cone_hint_adjust");
      };
       ConePrismParameters confirmedParameters = parameters(view);
       int previewVariant = stage == ConePrismStage.BODY && this.nearCenterline(confirmedParameters, eye, view.view())
          ? 1
          : view.coneShapeVariant();
      MutableComponent variant = Component.translatable(
         "fastformer.message.geometry_variant_cone",
         variantName(previewVariant),
         Component.translatable(view.conePlaneMode().translationKey()),
         Component.literal(GeometryNumbers.fixed(view.coneScaleX(), 2) + " x " + GeometryNumbers.fixed(view.coneScaleZ(), 2))
      );
      ConePrismParameters previewParameters = parameters(view, previewLocations, previewVariant, previewRadius);
      boolean precisionPointOnly = view.modifierHeld() && stage != ConePrismStage.ADJUST;
      boolean hideShapePreview = precisionPointOnly || stage == ConePrismStage.FACE;
      Set<BlockPos> previewGhost = hideShapePreview ? Set.of() : this.previewBlocks(previewParameters, view.fillMode());
      Set<BlockPos> confirmedGhost = hideShapePreview
         ? Set.of()
         : previewCandidate == null
            ? previewGhost
            : this.previewBlocks(confirmedParameters, view.fillMode());
      AxisGizmo gizmo = stage == ConePrismStage.ADJUST ? coneGizmo(previewParameters, view.coneGizmoLocal()) : null;
      return GeometryPreviewPlan.builder(points, hoveredPoint)
         .stage(Component.translatable(this.stage(view).labelKey()))
         .hud(variant, hint)
         .stageDisplay(this.stageDisplay(view, stage))
         .gizmo(gizmo)
         .blocks(GeometryPreviewBlocks.layers(confirmedGhost, previewGhost))
         .placementReady(
            stage == ConePrismStage.ADJUST
               && ConePrismGenerator.estimateScanCells(confirmedParameters) <= DETAILED_PREVIEW_SCAN_LIMIT
         )
         .textBlock(GeometryTextBlock.hidden(
            GeometryTextBlock.HINT_ID,
            GeometryTextBlock.Placement.BOTTOM_HINT
         ))
         .controlPoints(GeometryPreviewGuides.conePoints(previewParameters, view.pointRoles(), view.pointCount(), precisionPointOnly))
         .guides(hideShapePreview ? List.of() : GeometryPreviewGuides.coneHeight(previewParameters), List.of())
         .build();
   }

   private GeometryStageDisplay stageDisplay(GeometryWorkflowView view, ConePrismStage stage) {
      return switch (stage) {
         case FACE -> GeometryStageDisplay.modes(
            java.util.Arrays.stream(ConePlaneMode.values())
               .map(mode -> new GeometryStageDisplay.Mode(Component.translatable(mode.translationKey()), mode == view.conePlaneMode()))
               .toList()
         );
          case BODY -> GeometryStageDisplay.quiet();
          case ADJUST -> GeometryStageDisplay.value(
             Component.literal(signed(view.coneTopOffset().x) + ", " + signed(view.coneTopOffset().z))
          );
      };
   }

   private Set<BlockPos> previewBlocks(ConePrismParameters parameters, FillMode fillMode) {
      long scanCells = ConePrismGenerator.estimateScanCells(parameters);
      return GeometryPreviewBlocks.generatedOrFallback(
         scanCells,
         DETAILED_PREVIEW_SCAN_LIMIT,
         () -> parameters.heightPoint().isEmpty() && parameters.facePoints().size() >= parameters.planeMode().facePointCount()
            ? ConePrismGenerator.baseOutline(parameters, DETAILED_PREVIEW_SCAN_LIMIT)
            : ConePrismGenerator.generate(parameters, fillMode, DETAILED_PREVIEW_SCAN_LIMIT),
         () -> ConePrismGenerator.previewOutline(parameters, FALLBACK_PREVIEW_BLOCK_LIMIT)
      );
   }

   private double previewRadius(GeometryWorkflowView view, List<Vec3> previewLocations) {
      if (view.conePlaneMode() != ConePlaneMode.RADIUS) {
         return view.coneRadius();
      }
      if (view.coneStage() == ConePrismStage.FACE && view.pointCount() == 1 && previewLocations.size() > 1) {
         return Math.max(0.5, Math.round(horizontalRadius(previewLocations.get(1).subtract(previewLocations.getFirst())) * 2.0) * 0.5);
      }
      return view.coneRadius();
   }

   private List<Vec3> previewLocations(GeometryWorkflowView view, GeometryHit candidateHit, Vec3 eye) {
      List<Vec3> points = view.pointLocations();
      if (view.closed() || candidateHit == null || view.coneStage() == ConePrismStage.ADJUST) {
         return points;
      }
      Vec3 candidate;
      if (view.coneStage() == ConePrismStage.BODY) {
         candidate = this.heightPoint(parameters(view), eye, view.view(), candidateHit.conePoint(view.modifierHeld()));
      } else if (view.conePlaneMode() == ConePlaneMode.RADIUS && points.isEmpty()) {
         candidate = candidateHit.conePoint(view.modifierHeld());
      } else {
         candidate = candidateHit.conePoint(view.modifierHeld());
      }
      if (view.conePlaneMode() == ConePlaneMode.RADIUS && points.size() == 1) {
         double radius = Math.max(0.5, Math.round(horizontalRadius(candidate.subtract(points.getFirst())) * 2.0) * 0.5);
         candidate = radiusPoint(points.getFirst(), candidate, radius);
      }
      if (!points.isEmpty() && candidate.equals(points.getLast())) {
         return points;
      }
      ArrayList<Vec3> result = new ArrayList<>(points.size() + 1);
      result.addAll(points);
      result.add(candidate);
      return result;
   }

   private Vec3 heightPoint(ConePrismParameters parameters, Vec3 eye, Vec3 view, Vec3 fallback) {
      ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters);
      if (base == null) {
         return fallback;
      }
      Vec3 normal = base.normal();
      double projectedHeight = GeometryConstraints.rayAxisOffset(eye, view, base.center(), normal, 4.0);
      int height = (int)Math.round(projectedHeight);
      if (height == 0) {
         height = view.dot(normal) >= 0.0 ? 1 : -1;
      }
      return base.center().add(normal.scale(height));
   }

   private boolean nearCenterline(ConePrismParameters parameters, Vec3 eye, Vec3 view) {
      ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters);
      if (base == null) {
         return false;
      }
      Vec3 ray = view.lengthSqr() < 1.0E-7 ? new Vec3(0.0, 0.0, 1.0) : view.normalize();
      Vec3 axis = base.normal();
      Vec3 between = base.center().subtract(eye);
      double rayAxis = ray.dot(axis);
      double denominator = 1.0 - rayAxis * rayAxis;
      if (denominator < 1.0E-7) {
         return false;
      }
      double rayDistance = (between.dot(ray) - between.dot(axis) * rayAxis) / denominator;
      double axisDistance = (between.dot(ray) * rayAxis - between.dot(axis)) / denominator;
      if (rayDistance < 0.0) {
         return false;
      }
      double distance = eye.add(ray.scale(rayDistance)).distanceTo(base.center().add(axis.scale(axisDistance)));
      return distance <= Math.min(CENTERLINE_HIT_RADIUS, Math.max(0.25, base.radius() * 0.35));
   }

   private boolean scrollsTopScale(ConePrismParameters parameters, Vec3 view) {
      ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters);
      if (base == null) {
         return true;
      }
      Vec3 direction = view.lengthSqr() < 1.0E-7 ? new Vec3(0.0, 0.0, 1.0) : view.normalize();
      return Math.abs(direction.dot(base.normal())) >= 0.58;
   }

   private static double horizontalRadius(Vec3 vector) {
      return Math.hypot(vector.x, vector.z);
   }

   private static Vec3 radiusPoint(Vec3 center, Vec3 candidate, double radius) {
      Vec3 horizontal = new Vec3(candidate.x - center.x, 0.0, candidate.z - center.z);
      Vec3 direction = horizontal.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
      return center.add(direction.scale(radius));
   }

   private static ConePrismParameters parameters(GeometrySession session) {
      GeometryPoints.Cone points = session.conePoints();
      return new ConePrismParameters(
         points.facePointLocations(),
         points.heightPointLocation(),
         session.coneShapeVariant(),
         session.conePlaneMode(),
         session.coneRadius(),
         session.coneScaleX(),
         session.coneScaleZ(),
         session.coneTopScaleOffset(),
         session.coneTopOffset(),
         session.coneRotationRadians()
      );
   }

   private static ConePrismParameters parameters(GeometryWorkflowView view) {
      return parameters(view, view.pointLocations(), view.coneShapeVariant(), view.coneRadius());
   }

   private static ConePrismParameters parameters(
      GeometryWorkflowView view, List<Vec3> points, int shapeVariant, double radius
   ) {
      int facePointCount = Math.min(points.size(), view.conePlaneMode().facePointCount());
      List<Vec3> facePoints = List.copyOf(points.subList(0, facePointCount));
      Optional<Vec3> heightPoint = points.size() > facePointCount
         ? Optional.of(points.get(facePointCount))
         : Optional.empty();
      return new ConePrismParameters(
         facePoints,
         heightPoint,
         shapeVariant,
         view.conePlaneMode(),
         radius,
         view.coneScaleX(),
         view.coneScaleZ(),
         view.coneTopScaleOffset(),
         view.coneTopOffset(),
         view.coneRotationRadians()
      );
   }

   private static AxisGizmo coneGizmo(ConePrismParameters parameters, boolean local) {
      ConePrismGeometry geometry = ConePrismGeometry.from(parameters);
      if (geometry == null || !geometry.heightReady()) {
         return null;
      }
      double radius = Math.max(
         2.0,
         geometry.base().radius() * Math.max(geometry.scaleX(), geometry.scaleZ()) * Math.max(0.5, geometry.topScale())
      );
      TransformFrame frame = coneFrame(geometry, local);
      return new AxisGizmo(frame.origin(), radius * 1.25, Math.max(0.30, radius * 0.10), frame, coneHandles(local));
   }

   private static TransformFrame coneFrame(ConePrismGeometry geometry, boolean local) {
      Vec3 center = geometry.base().center().add(geometry.topCenter()).scale(0.5);
      ConePrismGeometry.Base base = geometry.base();
      return local
         ? TransformFrame.local(center, base.axisU(), base.normal(), base.axisV())
         : TransformFrame.world(center);
   }

   private static List<AxisGizmo.Handle> coneHandles(boolean local) {
      ArrayList<AxisGizmo.Handle> handles = new ArrayList<>();
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         handles.add(new AxisGizmo.Handle(AxisGizmo.Operation.MOVE, axis, AxisGizmo.Direction.POSITIVE, io.github.fastformer.fastplace.geometry.ControlPointRole.GIZMO_HANDLE));
         handles.add(new AxisGizmo.Handle(AxisGizmo.Operation.MOVE, axis, AxisGizmo.Direction.NEGATIVE, io.github.fastformer.fastplace.geometry.ControlPointRole.GIZMO_HANDLE));
         handles.add(new AxisGizmo.Handle(AxisGizmo.Operation.SCALE, axis, AxisGizmo.Direction.POSITIVE, io.github.fastformer.fastplace.geometry.ControlPointRole.GIZMO_HANDLE));
         handles.add(new AxisGizmo.Handle(AxisGizmo.Operation.SCALE, axis, AxisGizmo.Direction.NEGATIVE, io.github.fastformer.fastplace.geometry.ControlPointRole.GIZMO_HANDLE));
      }
      if (local) {
         handles.add(new AxisGizmo.Handle(
            AxisGizmo.Operation.ROTATE,
            AxisGizmo.Axis.Y,
            AxisGizmo.Direction.BIDIRECTIONAL,
            io.github.fastformer.fastplace.geometry.ControlPointRole.GIZMO_HANDLE
         ));
      }
      return List.copyOf(handles);
   }

   private static MutableComponent variantName(int variant) {
      String key = switch (Math.floorMod(variant, 3)) {
         case 0 -> "fastformer.geometry.cone_variant.column";
         case 1 -> "fastformer.geometry.cone_variant.cone";
         default -> "fastformer.geometry.cone_variant.frustum";
      };
      return Component.translatable(key);
   }

   private static String signed(double value) {
      String text = GeometryNumbers.fixed(value, 1);
      return value >= 0.0 ? "+" + text : text;
   }

   private Component missingPointHint(GeometrySession session) {
      GeometryPoints.Cone cone = session.conePoints();
      return switch (session.coneStage()) {
         case FACE -> switch (session.conePlaneMode()) {
            case RADIUS -> !cone.hasFacePoints()
               ? Component.translatable("fastformer.geometry.message.cone_radius_point_1")
               : Component.translatable("fastformer.geometry.message.cone_radius_point_2");
            case DIAMETER -> !cone.hasFacePoints()
               ? Component.translatable("fastformer.geometry.message.cone_diameter_point_1")
               : Component.translatable("fastformer.geometry.message.cone_diameter_point_2");
            case THREE_POINT -> switch (cone.pointCount()) {
               case 0 -> Component.translatable("fastformer.geometry.message.cone_three_point_1");
               case 1 -> Component.translatable("fastformer.geometry.message.cone_three_point_2");
               default -> Component.translatable("fastformer.geometry.message.cone_three_point_3");
            };
         };
         case BODY -> Component.translatable("fastformer.geometry.message.cone_body");
         case ADJUST -> Component.translatable("fastformer.geometry.message.cone_adjust");
      };
   }

   private Component nextPointHint(ConePlaneMode mode, int count) {
      return switch (mode) {
         case RADIUS -> count == 0
            ? Component.translatable("fastformer.geometry.message.cone_radius_point_1")
            : Component.translatable("fastformer.geometry.message.cone_radius_point_2");
         case DIAMETER -> count == 0
            ? Component.translatable("fastformer.geometry.message.cone_diameter_point_1")
            : Component.translatable("fastformer.geometry.message.cone_diameter_point_2");
         case THREE_POINT -> switch (count) {
            case 0 -> Component.translatable("fastformer.geometry.message.cone_three_point_1");
            case 1 -> Component.translatable("fastformer.geometry.message.cone_three_point_2");
            default -> Component.translatable("fastformer.geometry.message.cone_three_point_3");
         };
      };
   }
}
