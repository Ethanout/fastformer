package io.github.fastformer.client.render.core;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.InteractionContext;
import io.github.fastformer.client.input.InteractionIntentProvider;
import io.github.fastformer.client.input.InteractionIntentResolver;
import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.client.render.GizmoViewScale;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;

/** Resolves operation pointer targets against the persistent client workspace. */
final class WorkspaceInteractionResolver {
   private static final double REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final double EDGE_THRESHOLD = 0.12;
   private static final IdentityHashMap<ClientSelectionPart, Map<BlockPos, ClientBlockSnapshot>> RESOLVED_CACHE =
      new IdentityHashMap<>();
   private static long resolvedCacheRevision = Long.MIN_VALUE;

   private WorkspaceInteractionResolver() {
   }

   static List<InteractionIntentProvider> providers() {
      return List.of(
         WorkspaceInteractionResolver::resolveDraggedFace,
         WorkspaceInteractionResolver::resolveGizmo,
         WorkspaceInteractionResolver::resolveFace,
         WorkspaceInteractionResolver::resolvePart,
         WorkspaceInteractionResolver::resolveSelectionCreate
      );
   }

   static Optional<OperationInteractionIntent> resolveCurrentIntent() {
      InteractionContext context = InteractionContext.capture(net.minecraft.client.Minecraft.getInstance());
      return InteractionIntentResolver.resolve(context, providers());
   }

   static OperationInteractionIntent.Gizmo currentGizmo() {
      return resolveCurrentIntent()
         .filter(OperationInteractionIntent.Gizmo.class::isInstance)
         .map(OperationInteractionIntent.Gizmo.class::cast)
         .orElse(null);
   }

   static void clearCache() {
      RESOLVED_CACHE.clear();
      resolvedCacheRevision = Long.MIN_VALUE;
   }

   private static Optional<OperationInteractionIntent> resolveGizmo(InteractionContext context) {
      if (!ClientOperationController.active() || context.alternative()) {
         return Optional.empty();
      }
      List<OperationInteractionIntent.Gizmo> targets = new ArrayList<>();
      var workspace = ClientOperationController.workspace();
      List<ClientSelectionPart> parts = workspace.parts();
      pruneCache(parts);
      for (ClientSelectionPart part : parts) {
         Map<BlockPos, ClientBlockSnapshot> resolved = resolvePartBlocks(part);
         if (resolved.isEmpty()) {
            continue;
         }
         Vec3 center = OccupiedBlockBounds.from(resolved.keySet()).orElseThrow().center();
         GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
         AxisGizmo gizmo = partGizmo(part, center, scale).withTextComponent(
            io.github.fastformer.fastplace.geometry.GizmoTextComponent.pointLevel()
         );
         AxisGizmo.Hit hit = gizmo.hitTest(context.eye(), context.view(), REACH);
         if (hit != null) {
            targets.add(new OperationInteractionIntent.Gizmo(part.id(), false, gizmo, hit));
         }
      }
      if (workspace.selectedIds().size() > 1) {
         OccupiedBlockBounds group = workspace.selectedParts().stream()
            .map(WorkspaceInteractionResolver::resolvePartBlocks)
            .filter(values -> !values.isEmpty())
            .map(values -> OccupiedBlockBounds.from(values.keySet()).orElseThrow())
            .reduce(OccupiedBlockBounds::union)
            .orElse(null);
         if (group != null) {
            Vec3 center = group.center();
            GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
            boolean includesPrism = workspace.selectedParts().stream()
               .anyMatch(part -> part.selection() != null && part.selection().prism() != null);
            AxisGizmo gizmo = (includesPrism
               ? AxisGizmo.inFrame(
                  TransformFrame.world(center), scale.axisLength() * 1.12, scale.handleRadius() * 1.12,
                  AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE
               )
               : AxisGizmo.inFrame(
                  TransformFrame.world(center), scale.axisLength() * 1.12, scale.handleRadius() * 1.12,
                  AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE
               )).withTextComponent(io.github.fastformer.fastplace.geometry.GizmoTextComponent.pointLevel());
            AxisGizmo.Hit hit = gizmo.hitTest(context.eye(), context.view(), REACH);
            if (hit != null) {
               targets.add(new OperationInteractionIntent.Gizmo(0, true, gizmo, hit));
            }
         }
      }
      return targets.stream()
         .min(Comparator
            .comparingDouble((OperationInteractionIntent.Gizmo target) -> target.hit().rayDistance())
            .thenComparingDouble(target -> target.hit().handleDistance()))
         .map(OperationInteractionIntent.class::cast);
   }

   static AxisGizmo partGizmo(ClientSelectionPart part, Vec3 center, GizmoViewScale scale) {
      return part.orientedCuboid()
         ? AxisGizmo.inFrame(
            TransformFrame.world(center), scale.axisLength(), scale.handleRadius(),
            AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE
         )
         : AxisGizmo.inFrame(
            TransformFrame.world(center), scale.axisLength(), scale.handleRadius(),
            AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE
         );
   }

   private static Optional<OperationInteractionIntent> resolvePart(InteractionContext context) {
      if (!ClientOperationController.active() || context.alternative()
         || InteractionContext.directlyNearVanillaBlock(context.minecraft())) {
         return Optional.empty();
      }
      int bestId = 0;
      double bestDistance = Double.POSITIVE_INFINITY;
      List<ClientSelectionPart> parts = ClientOperationController.workspace().parts();
      pruneCache(parts);
      for (ClientSelectionPart part : parts) {
         Map<BlockPos, ClientBlockSnapshot> resolved = resolvePartBlocks(part);
         if (resolved.isEmpty()) {
            continue;
         }
         OccupiedBlockBounds occupied = OccupiedBlockBounds.from(resolved.keySet()).orElseThrow();
         AABB bounds = occupied.aabb();
         OperationGeometry.RayHit hit = OperationGeometry.raycast(bounds.inflate(0.015), context.eye(), context.view(), REACH);
         boolean frameHit = hit != null && (context.control() || nearEdge(hit.point(), bounds));
         Vec3 label = occupied.center().add(0.0, 0.22, 0.0);
         double labelRayDistance = Math.max(0.0, label.subtract(context.eye()).dot(context.view()));
         double labelDistance = context.eye().add(context.view().scale(labelRayDistance)).distanceTo(label);
         boolean labelHit = labelRayDistance <= REACH && labelDistance <= 0.22;
         double distance = labelHit ? labelRayDistance : frameHit ? hit.distance() : Double.POSITIVE_INFINITY;
         if (distance < bestDistance) {
            bestDistance = distance;
            bestId = part.id();
         }
      }
      return bestId == 0
         ? Optional.empty()
         : Optional.of(new OperationInteractionIntent.Part(bestId, bestDistance));
   }

   private static Optional<OperationInteractionIntent> resolveDraggedFace(InteractionContext context) {
      OperationGeometry.RayHit dragged = FastPlaceClientInput.workspaceFaceDragHit();
      int draggedPartId = FastPlaceClientInput.workspaceFaceDragPartId();
      if (dragged != null && draggedPartId > 0) {
         ClientSelectionPart part = ClientOperationController.workspace().part(draggedPartId).orElse(null);
         if (part != null && part.editability() == ClientSelectionPart.Editability.FREE) {
            AABB bounds = selectionBounds(part);
            if (bounds != null) {
               return Optional.of(new OperationInteractionIntent.Face(draggedPartId, bounds, dragged, true));
            }
         }
      }
      return Optional.empty();
   }

   private static Optional<OperationInteractionIntent> resolveFace(InteractionContext context) {
      if (!ClientOperationController.active() || context.control() || context.alternative()
         || InteractionContext.directlyNearVanillaBlock(context.minecraft())) {
         return Optional.empty();
      }
      OperationInteractionIntent.Face best = null;
      for (ClientSelectionPart part : ClientOperationController.workspace().parts()) {
         if (part.editability() == ClientSelectionPart.Editability.LOCKED) {
            continue;
         }
         AABB bounds = selectionBounds(part);
         if (bounds == null) {
            continue;
         }
         OperationGeometry.RayHit hit = OperationGeometry.raycast(bounds.inflate(0.012), context.eye(), context.view(), REACH);
         if (hit != null && (best == null || hit.distance() < best.hit().distance())) {
            best = new OperationInteractionIntent.Face(
               part.id(), bounds, hit, ClientOperationController.canAdjustAabbFace(part)
            );
         }
      }
      return Optional.ofNullable(best).map(OperationInteractionIntent.class::cast);
   }

   private static Optional<OperationInteractionIntent> resolveSelectionCreate(InteractionContext context) {
      if (context.alternative()) {
         return Optional.empty();
      }
      BlockPos point;
      boolean workspaceCreation = ClientOperationController.active()
         && (ClientOperationController.activeSelectionTransformed() || context.control());
      if (workspaceCreation) {
         if (context.minecraft().level == null) {
            return Optional.empty();
         }
         BlockHitResult hit = LongRangeBlockRaycast.clip(
            context.minecraft().level, context.player(), context.eye(), context.view()
         ).hit();
         point = hit.getType() == Type.BLOCK ? hit.getBlockPos() : null;
      } else {
         if (context.nearVanillaBlock()
            || !FastPlaceClientPreviewCore.operationActive()
            || FastPlaceClientPreviewCore.operationSelectionConfirmed()
            || FastPlaceClientPreviewCore.operationCuboid()
               && !FastPlaceClientPreviewCore.operationNeedsFirst()
               && !FastPlaceClientPreviewCore.operationNeedsSecond()) {
            return Optional.empty();
         }
         point = FastPlaceClientPreviewCore.operationCandidatePoint();
      }
      return point == null
         ? Optional.empty()
         : Optional.of(new OperationInteractionIntent.CreateSelection(point));
   }

   static AABB outlineBounds(ClientSelectionPart part, AABB occupiedBounds) {
      if (part.editability() == ClientSelectionPart.Editability.FREE) {
         AABB selection = selectionBounds(part);
         if (selection != null) {
            return selection;
         }
      }
      return occupiedBounds;
   }

   static AABB selectionBounds(ClientSelectionPart part) {
      if (part == null) {
         return null;
      }
      if (part.selection() != null && part.axisAlignedCuboid() && part.transform().rotation().equals(Vec3.ZERO)) {
         Vec3 translation = part.transform().translation();
         return part.selection().bounds().move(translation.x, translation.y, translation.z);
      }
      Map<BlockPos, ClientBlockSnapshot> resolved = resolveBasePart(part);
      OccupiedBlockBounds occupied = OccupiedBlockBounds.from(resolved.keySet()).orElse(null);
      return occupied == null ? null : occupied.aabb();
   }

   static Map<BlockPos, ClientBlockSnapshot> resolvePartBlocks(ClientSelectionPart part) {
      long revision = ClientOperationController.workspace().revision();
      if (resolvedCacheRevision != revision) {
         clearCache();
         resolvedCacheRevision = revision;
      }
      return RESOLVED_CACHE.computeIfAbsent(part, WorkspacePreviewComposer::resolveForRendering);
   }

   static Map<BlockPos, ClientBlockSnapshot> resolveBasePart(ClientSelectionPart part) {
      if (part.transform().repeats().equals(io.github.fastformer.fastplace.OperationStackRegion.origin())) {
         return resolvePartBlocks(part);
      }
      WorkspaceTransform transform = part.transform().withoutRepeats();
      return WorkspacePreviewComposer.canResolveForRendering(part.blocks(), transform)
         ? WorkspacePreviewComposer.resolveValues(part.blocks(), transform)
         : Map.of();
   }

   static void pruneCache(List<ClientSelectionPart> currentParts) {
      Set<ClientSelectionPart> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      live.addAll(currentParts);
      RESOLVED_CACHE.keySet().removeIf(part -> !live.contains(part));
   }

   private static boolean nearEdge(Vec3 point, AABB bounds) {
      int boundaryAxes = 0;
      if (Math.min(Math.abs(point.x - bounds.minX), Math.abs(point.x - bounds.maxX)) <= EDGE_THRESHOLD) {
         boundaryAxes++;
      }
      if (Math.min(Math.abs(point.y - bounds.minY), Math.abs(point.y - bounds.maxY)) <= EDGE_THRESHOLD) {
         boundaryAxes++;
      }
      if (Math.min(Math.abs(point.z - bounds.minZ), Math.abs(point.z - bounds.maxZ)) <= EDGE_THRESHOLD) {
         boundaryAxes++;
      }
      return boundaryAxes >= 2;
   }
}
