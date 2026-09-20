package io.github.fastformer.client.render.core;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.input.InteractionContext;
import io.github.fastformer.client.input.InteractionIntentProvider;
import io.github.fastformer.client.input.InteractionIntentResolver;
import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.interaction.InteractionGeometry;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.SelectionGizmoInteraction;
import io.github.fastformer.client.interaction.InteractionComponents;
import io.github.fastformer.client.interaction.InteractionVisibility;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.client.interaction.PartFrameInteraction;
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
      if (!ClientOperationController.active() || ClientOperationController.workspace().locked()) {
         return Optional.empty();
      }
      List<OperationInteractionIntent.Gizmo> targets = new ArrayList<>();
      var scene = ClientOperationController.interactionScene();
      for (var object : scene.parts().values()) {
         ClientSelectionPart part = object.source();
         if (!InteractionVisibility.isVisible(object.gizmo(),
            ClientOperationController.workspace().selectedIds().contains(part.id()))) {
            continue;
         }
         AABB interactionBounds = object.bounds();
         if (interactionBounds == null) {
            continue;
         }
         Vec3 center = interactionBounds.getCenter();
         GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
         List<AxisGizmo> gizmos = SelectionGizmoInteraction.resolvePart(object.gizmo(), scale).gizmos();
         List<AxisGizmo.Hit> hits = new ArrayList<>(gizmos.size());
         List<AxisGizmo> hitGizmos = new ArrayList<>(gizmos.size());
         for (AxisGizmo candidate : gizmos) {
            AxisGizmo.Hit candidateHit = candidate.hitTest(context.eye(), context.view(), REACH);
            if (candidateHit != null) {
               hits.add(candidateHit);
               hitGizmos.add(candidate);
            }
         }
         AxisGizmo.Hit hit = AxisGizmo.preferHit(hits);
         if (hit != null) {
            for (int index = 0; index < hits.size(); index++) {
               if (hits.get(index) != hit) {
                  continue;
               }
               AxisGizmo hitGizmo = hitGizmos.get(index);
               targets.add(new OperationInteractionIntent.Gizmo(
                  part.id(),
                  false,
                  hitGizmo,
                  hit
               ));
               break;
            }
         }
      }
      var groupObject = scene.groupGizmo();
      if (groupObject != null) {
         AABB groupAabb = groupObject.require(InteractionComponents.WORLD_BOUNDS);
         if (groupAabb != null) {
            Vec3 center = groupAabb.getCenter();
            GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
            AxisGizmo gizmo = SelectionGizmoInteraction.resolveGroup(groupObject, scale);
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

   private static Optional<OperationInteractionIntent> resolvePart(InteractionContext context) {
      if (!ClientOperationController.active() || context.alternative()
         || InteractionContext.directlyNearVanillaBlock(context.minecraft())) {
         return Optional.empty();
      }
      var workspace = ClientOperationController.workspace();
      Set<BlockPos> failedTargets = ClientOperationController.failedWorkspaceTargets();
      return resolvePartTarget(
         ClientOperationController.interactionScene(), context.eye(), context.view(), context.control(),
         failedTargets, workspace.locked()
      ).map(OperationInteractionIntent.class::cast);
   }

   static Optional<OperationInteractionIntent.Part> resolvePartTarget(
      SelectionInteractionScene scene, Vec3 eye, Vec3 view, boolean control,
      Set<BlockPos> failedTargets, boolean workspaceLocked
   ) {
      if (workspaceLocked || scene.parts().isEmpty()) {
         return Optional.empty();
      }
      int bestId = 0;
      OperationInteractionIntent.PartSurface bestSurface = OperationInteractionIntent.PartSurface.LABEL;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (var object : scene.parts().values()) {
         ClientSelectionPart part = object.source();
         AABB bounds = object.bounds();
         if (!WorkspacePartInteractionCapabilities.canSelect(part, bounds)) {
            continue;
         }
         OperationGeometry.RayHit hit = PartFrameInteraction.hit(object.frame(), eye, view, REACH, control);
         boolean frameHit = hit != null
            && !hitsFailedTarget(hit, failedTargets);
         var labelDistance = InteractionGeometry.hitDistance(object.label(), eye, view, REACH);
         double distance = labelDistance.isPresent() ? labelDistance.getAsDouble()
            : frameHit ? hit.distance() : Double.POSITIVE_INFINITY;
         if (distance < bestDistance) {
            bestDistance = distance;
            bestId = part.id();
            bestSurface = labelDistance.isPresent() ? OperationInteractionIntent.PartSurface.LABEL : OperationInteractionIntent.PartSurface.FRAME;
         }
      }
      return bestId == 0 ? Optional.empty() : Optional.of(new OperationInteractionIntent.Part(bestId, bestDistance, bestSurface));
   }

   private static Optional<OperationInteractionIntent> resolveDraggedFace(InteractionContext context) {
      var inputSession = FastPlaceClientInput.currentSession();
      OperationGeometry.RayHit dragged = PointerDragSnapshotView.faceHit(inputSession);
      int draggedPartId = PointerDragSnapshotView.facePartId(inputSession);
      if (dragged != null && draggedPartId > 0) {
         ClientSelectionPart part = ClientOperationController.workspace().part(draggedPartId).orElse(null);
         if (WorkspacePartInteractionCapabilities.canEditSource(part)) {
            AABB bounds = ClientOperationController.interactionScene().bounds(part.id());
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
      Set<BlockPos> failedTargets = ClientOperationController.failedWorkspaceTargets();
      for (ClientSelectionPart part : ClientOperationController.workspace().parts()) {
         if (!WorkspacePartInteractionCapabilities.canEditSource(part)) {
            continue;
         }
         AABB bounds = ClientOperationController.interactionScene().bounds(part.id());
         if (bounds == null) {
            continue;
         }
         OperationGeometry.RayHit hit = OperationGeometry.raycast(bounds.inflate(0.012), context.eye(), context.view(), REACH);
         if (hit != null && !hitsFailedTarget(hit, failedTargets)
            && (best == null || hit.distance() < best.hit().distance())) {
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
         point = selectionCreationPoint(hit);
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

   static BlockPos selectionCreationPoint(BlockHitResult hit) {
      return hit != null && hit.getType() == Type.BLOCK
         ? hit.getBlockPos().relative(hit.getDirection())
         : null;
   }

   static Map<BlockPos, ClientBlockSnapshot> resolvePartBlocks(ClientSelectionPart part) {
      long revision = ClientOperationController.workspace().revision();
      if (resolvedCacheRevision != revision) {
         clearCache();
         resolvedCacheRevision = revision;
      }
      return RESOLVED_CACHE.computeIfAbsent(part, WorkspacePreviewComposer::resolveForRendering);
   }

   /** Resolves the portion still visible after a rejected workspace submission. */
   static Map<BlockPos, ClientBlockSnapshot> resolveVisiblePartBlocks(ClientSelectionPart part) {
      Map<BlockPos, ClientBlockSnapshot> resolved = resolvePartBlocks(part);
      Set<BlockPos> failed = ClientOperationController.failedWorkspaceTargets();
      return withoutFailedTargets(resolved, failed);
   }

   static <T> Map<BlockPos, T> withoutFailedTargets(Map<BlockPos, T> blocks, Set<BlockPos> failed) {
      if (blocks == null || blocks.isEmpty() || failed == null || failed.isEmpty()) {
         return blocks == null ? Map.of() : blocks;
      }
      java.util.LinkedHashMap<BlockPos, T> visible = new java.util.LinkedHashMap<>(blocks);
      visible.keySet().removeAll(failed);
      return Map.copyOf(visible);
   }

   static boolean hitsFailedTarget(OperationGeometry.RayHit hit, Set<BlockPos> failedTargets) {
      if (hit == null || failedTargets == null || failedTargets.isEmpty()) {
         return false;
      }
      Vec3 inside = hit.point().subtract(hit.normal().scale(0.02));
      return failedTargets.contains(BlockPos.containing(inside));
   }

   static void pruneCache(List<ClientSelectionPart> currentParts) {
      Set<ClientSelectionPart> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      live.addAll(currentParts);
      RESOLVED_CACHE.keySet().removeIf(part -> !live.contains(part));
   }

}
