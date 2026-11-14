package io.github.fastformer.client.operation.pointer;

import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.interaction.pointer.PointerResolver;
import io.github.fastformer.client.interaction.InteractionComponents;
import io.github.fastformer.client.interaction.InteractionGeometry;
import io.github.fastformer.client.interaction.PartFrameInteraction;
import io.github.fastformer.client.interaction.SelectionGizmoInteraction;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.InteractionIntentProvider;
import io.github.fastformer.client.interaction.intent.InteractionIntentResolver;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/** Resolves operation pointer targets against the persistent client workspace. */
public final class WorkspacePointerResolver {
   private static final double REACH = LongRangeBlockRaycast.MAX_REACH;
   private final IdentityHashMap<ClientSelectionPart, Map<BlockPos, ClientBlockSnapshot>> resolvedCache =
      new IdentityHashMap<>();
   private long resolvedCacheRevision = Long.MIN_VALUE;
   private java.util.UUID resolvedCacheOwner;
   private final ClientSelectionSession selection;
   private final BooleanSupplier smartEditing;
   private final Supplier<Set<BlockPos>> failedTargets;
   private final List<InteractionIntentProvider> providers;
   private SelectionPointerState frame;

   public WorkspacePointerResolver(ClientSelectionSession selection, BooleanSupplier smartEditing,
      Supplier<Set<BlockPos>> failedTargets, PointerResolver pointer, InteractionIntentProvider creation) {
      this.selection = Objects.requireNonNull(selection, "selection");
      this.frame = SelectionPointerState.empty(selection.interactionOwnerId());
      this.smartEditing = Objects.requireNonNull(smartEditing, "smartEditing");
      this.failedTargets = Objects.requireNonNull(failedTargets, "failedTargets");
      Objects.requireNonNull(pointer, "pointer");
      Objects.requireNonNull(creation, "creation");
      this.providers = List.of(this::resolveDraggedFace, this::resolveGizmo,
         context -> resolveFace(context, pointer), this::resolvePart, creation);
   }

   List<InteractionIntentProvider> providers() {
      return providers;
   }

   public Optional<OperationInteractionIntent> resolve(InteractionContext context) {
      return InteractionIntentResolver.resolve(context, providers());
   }

   /** Capture once at the visual frame boundary; physical input still calls resolve. */
   public SelectionPointerState captureFrame(InteractionContext context) {
      frame = SelectionPointerState.empty(selection.interactionOwnerId());
      frame = new SelectionPointerState(selection.interactionOwnerId(), resolve(context));
      return frame;
   }

   /** Never resolves targets while drawing, and never exposes a previous session's hit. */
   public SelectionPointerState frame() {
      if (!frame.owner().equals(selection.interactionOwnerId()))
         frame = SelectionPointerState.empty(selection.interactionOwnerId());
      return frame;
   }

   public OperationInteractionIntent.Gizmo gizmo(InteractionContext context) {
      return resolve(context)
         .filter(OperationInteractionIntent.Gizmo.class::isInstance)
         .map(OperationInteractionIntent.Gizmo.class::cast)
         .orElse(null);
   }

   public void clear() {
      frame = SelectionPointerState.empty(selection.interactionOwnerId());
      clearModels();
   }

   private void clearModels() {
      resolvedCache.clear();
      resolvedCacheRevision = Long.MIN_VALUE;
      resolvedCacheOwner = null;
   }

   private Optional<OperationInteractionIntent> resolveGizmo(InteractionContext context) {
      if (smartEditing.getAsBoolean()) return Optional.empty();
      if (selection.workspace().isEmpty() || selection.hasDraft() || selection.workspace().locked()) {
         return Optional.empty();
      }
      List<OperationInteractionIntent.Gizmo> targets = new ArrayList<>();
      var scene = selection.interactionScene();
      for (var object : scene.parts().values()) {
         ClientSelectionPart part = object.source();
         if (!SelectionGizmoInteraction.partGizmoVisible(scene, object.gizmo(),
            selection.workspace().selectedIds().contains(part.id()))) {
            continue;
         }
         AABB interactionBounds = object.bounds();
         if (interactionBounds == null) {
            continue;
         }
         Vec3 center = interactionBounds.getCenter();
         GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
         addGizmoTarget(targets, part.id(), false, SelectionGizmoInteraction.resolvePart(object.gizmo(), scale), context);
      }
      var groupObject = scene.groupGizmo();
      if (groupObject != null) {
         AABB groupAabb = groupObject.require(InteractionComponents.WORLD_BOUNDS);
         if (groupAabb != null) {
            Vec3 center = groupAabb.getCenter();
            GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
            addGizmoTarget(targets, 0, true, SelectionGizmoInteraction.resolveGroup(groupObject, scale), context);
         }
      }
      return targets.stream()
         .filter(target -> gizmoPrecedesOtherParts(target, scene, context))
         .min(Comparator
            .comparingDouble((OperationInteractionIntent.Gizmo target) -> target.hit().rayDistance())
            .thenComparingDouble(target -> target.hit().handleDistance()))
         .map(OperationInteractionIntent.class::cast);
   }

   private static void addGizmoTarget(List<OperationInteractionIntent.Gizmo> targets, int partId, boolean common,
      SelectionGizmoInteraction.PartGeometry geometry, InteractionContext context) {
      List<OperationInteractionIntent.Gizmo> candidates = new ArrayList<>();
      for (AxisGizmo gizmo : geometry.gizmos()) {
         AxisGizmo.Hit hit = gizmo.hitTest(context.eye(), context.view(), REACH);
         if (hit != null) candidates.add(new OperationInteractionIntent.Gizmo(partId, common, gizmo, hit));
      }
      AxisGizmo.Hit preferred = AxisGizmo.preferHit(candidates.stream().map(OperationInteractionIntent.Gizmo::hit).toList());
      candidates.stream().filter(candidate -> candidate.hit() == preferred).findFirst().ifPresent(targets::add);
   }

   private boolean gizmoPrecedesOtherParts(
      OperationInteractionIntent.Gizmo target, SelectionInteractionScene scene, InteractionContext context
   ) {
      Set<Integer> owners = target.common()
         ? scene.groupGizmo().require(InteractionComponents.GROUP_GIZMO).members().keySet()
         : Set.of(target.partId());
      for (var part : scene.parts().values()) {
         if (owners.contains(part.source().id()) || part.bounds() == null) continue;
         var hit = hitPart(part.source(), part.bounds(), context.eye(), context.view(), context);
         // A handle can be inside its own model, but must not steal a click through another model.
         if (hit != null && hit.distance() + 0.02 < target.hit().rayDistance()) return false;
      }
      return true;
   }

   private Optional<OperationInteractionIntent> resolvePart(InteractionContext context) {
      if (smartEditing.getAsBoolean()) return Optional.empty();
      if (selection.workspace().isEmpty() || selection.hasDraft() || context.alternative()) {
         return Optional.empty();
      }
      var workspace = selection.workspace();
      Set<BlockPos> failed = failedTargets.get();
      return resolvePartTarget(
         selection.interactionScene(), context.eye(), context.view(), context.control(), failed, workspace.locked(), context
      ).map(OperationInteractionIntent.class::cast);
   }

   Optional<OperationInteractionIntent.Part> resolvePartTarget(
      SelectionInteractionScene scene, Vec3 eye, Vec3 view, boolean control,
      Set<BlockPos> failedTargets, boolean workspaceLocked
   ) {
      return resolvePartTarget(scene, eye, view, control, failedTargets, workspaceLocked, null);
   }

   private Optional<OperationInteractionIntent.Part> resolvePartTarget(
      SelectionInteractionScene scene, Vec3 eye, Vec3 view, boolean control,
      Set<BlockPos> failedTargets, boolean workspaceLocked, InteractionContext context
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
         OperationGeometry.RayHit hit = part.smart() ? hitPart(part, bounds, eye, view, context)
            : PartFrameInteraction.hit(object.frame(), eye, view, REACH, control);
         boolean frameHit = hit != null
            && !hitsFailedTarget(hit, failedTargets);
         var labelDistance = part.smart() ? java.util.OptionalDouble.empty()
            : InteractionGeometry.hitDistance(object.label(), eye, view, REACH);
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

   private Optional<OperationInteractionIntent> resolveDraggedFace(InteractionContext context) {
      var drag = selection.gestures().face();
      OperationGeometry.RayHit dragged = drag == null ? null : drag.hit();
      int draggedPartId = drag == null ? 0 : drag.baseline().id();
      if (dragged != null && draggedPartId > 0) {
         ClientSelectionPart part = selection.workspace().part(draggedPartId).orElse(null);
         if (WorkspacePartInteractionCapabilities.canEditSource(part)) {
            AABB bounds = selection.interactionScene().bounds(part.id());
            if (bounds != null) {
               return Optional.of(new OperationInteractionIntent.Face(draggedPartId, bounds, dragged, true));
            }
         }
      }
      return Optional.empty();
   }

   private Optional<OperationInteractionIntent> resolveFace(InteractionContext context, PointerResolver pointer) {
      if (smartEditing.getAsBoolean()) return Optional.empty();
      if (selection.workspace().isEmpty() || selection.hasDraft()
         || selection.workspace().locked() || context.control() || context.alternative()) {
         return Optional.empty();
      }
      OperationInteractionIntent.Face best = null;
      double worldDistance = REACH;
      if (context.minecraft() != null && context.minecraft().level != null) {
         BlockHitResult worldHit = pointer.outline(context.player()).hit();
         if (worldHit.getType() == Type.BLOCK) worldDistance = context.eye().distanceTo(worldHit.getLocation());
      }
      Set<BlockPos> failed = failedTargets.get();
      for (ClientSelectionPart part : selection.workspace().parts()) {
         AABB bounds = selection.interactionScene().bounds(part.id());
         if (!WorkspacePartInteractionCapabilities.canSelect(part, bounds)) {
            continue;
         }
         OperationGeometry.RayHit hit = hitPart(part, bounds, context.eye(), context.view(), context);
         if (hit != null && facePrecedesWorldBlock(hit.distance(), worldDistance) && !hitsFailedTarget(hit, failed)
            && (best == null || hit.distance() < best.hit().distance())) {
            best = new OperationInteractionIntent.Face(
               part.id(), bounds, hit, part.canAdjustGeometry()
            );
         }
      }
      if (best == null) return Optional.empty();
      ClientSelectionPart part = selection.workspace().part(best.partId()).orElseThrow();
      if (part.smart() || !WorkspacePartInteractionCapabilities.canEditSource(part)) {
         return Optional.of(new OperationInteractionIntent.Part(
            best.partId(), best.hit().distance(), OperationInteractionIntent.PartSurface.FRAME));
      }
      return Optional.of(best);
   }

   static boolean facePrecedesWorldBlock(double faceDistance, double worldDistance) {
      return faceDistance <= worldDistance + 0.02;
   }

   private OperationGeometry.RayHit hitPart(ClientSelectionPart part, AABB bounds, Vec3 eye, Vec3 view, InteractionContext context) {
      if (!part.smart()) return OperationGeometry.raycast(bounds.inflate(0.012), eye, view, REACH);
      if (OperationGeometry.raycast(bounds.inflate(0.5), eye, view, REACH) == null) return null;
      var minecraft = context == null ? null : context.minecraft();
      return io.github.fastformer.fastplace.selection.SmartSelectionRaycast.hit(visibleBlocks(part),
         minecraft == null ? null : minecraft.level,
         minecraft == null || minecraft.player == null ? net.minecraft.world.phys.shapes.CollisionContext.empty()
            : net.minecraft.world.phys.shapes.CollisionContext.of(minecraft.player), eye, view, REACH);
   }

   Map<BlockPos, ClientBlockSnapshot> resolvePartBlocks(ClientSelectionPart part) {
      long revision = selection.workspace().revision();
      if (resolvedCacheRevision != revision || !selection.interactionOwnerId().equals(resolvedCacheOwner)) {
         clearModels();
         resolvedCacheRevision = revision;
         resolvedCacheOwner = selection.interactionOwnerId();
      }
      return resolvedCache.computeIfAbsent(part, WorkspacePreviewComposer::resolveForRendering);
   }

   /** Resolves the portion still visible after a rejected workspace submission. */
   public Map<BlockPos, ClientBlockSnapshot> visibleBlocks(ClientSelectionPart part) {
      Map<BlockPos, ClientBlockSnapshot> resolved = resolvePartBlocks(part);
      Set<BlockPos> failed = failedTargets.get();
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

   public void prune(List<ClientSelectionPart> currentParts) {
      Set<ClientSelectionPart> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      live.addAll(currentParts);
      resolvedCache.keySet().removeIf(part -> !live.contains(part));
   }

}
