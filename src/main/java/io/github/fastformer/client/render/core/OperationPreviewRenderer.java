package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.PreviewStyle;

import static io.github.fastformer.client.gizmo.GizmoRenderer.operationGizmoAlpha;
import static io.github.fastformer.client.render.core.FastPlaceClientPreviewCore.*;
import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.client.gizmo.GizmoRenderer;
import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.gizmo.OperationGizmoPresentation;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.InteractionComponents;
import io.github.fastformer.client.interaction.InteractionTooltip;
import io.github.fastformer.client.interaction.PartLabelInteraction;
import io.github.fastformer.client.interaction.SelectionGizmoInteraction;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.render.WorkspacePointerPrompt;
import io.github.fastformer.client.render.WorkspacePreviewRenderer;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.fastplace.geometry.*;
import io.github.fastformer.fastplace.geometry.generation.*;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Renders operation selection volumes and workspace editing guides. */
final class OperationPreviewRenderer {
   private OperationPreviewRenderer() {
   }

   static float selectionOpacity(boolean alt) { return alt ? 0.35F : 1.0F; }

   static void renderDraft(RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent intent) {
      var draft = ClientOperationController.selectionDraft();
      if (draft.points().isEmpty()) return;
      var points = new ArrayList<>(draft.points());
      if (intent instanceof OperationInteractionIntent.CreateSelection create && !points.contains(create.point())) points.add(create.point());
      var selection = io.github.fastformer.client.operation.selection.SelectionDraftPreview.volume(draft,
         intent instanceof OperationInteractionIntent.CreateSelection create ? create.point() : null);
      var pose = event.getPoseStack();
      var buffers = minecraft.renderBuffers().bufferSource();
      var camera = event.getCamera().getPosition();
      float opacity = selectionOpacity(FastPlaceClientInput.modifierHeld());
      pose.pushPose();
      pose.translate(-camera.x, -camera.y, -camera.z);
      // The draft volume is a candidate: dashed outline, no face. Clicked points stay solid.
      if (selection != null) {
         renderOperationVolume(pose, buffers.getBuffer(GHOST_OUTLINE_LINES), selection, Vec3.ZERO, 0.8F * opacity);
      } else {
         for (int i = 1; i < points.size(); i++) renderOperationOutlineLine(pose, buffers.getBuffer(GHOST_OUTLINE_LINES),
            Vec3.atCenterOf(points.get(i - 1)), Vec3.atCenterOf(points.get(i)), 0.8F * opacity);
      }
      for (var point : draft.points()) LevelRenderer.renderLineBox(pose, buffers.getBuffer(GHOST_OUTLINE_LINES),
         new AABB(point).inflate(PreviewStyle.OUTLINE_INFLATE), inkRed(), inkGreen(), inkBlue(), PreviewStyle.OUTLINE_ALPHA * opacity);
      buffers.endBatch(GHOST_OUTLINE_LINES);
      pose.popPose();
   }

   static void renderOperationSelection(RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player, OperationPreviewPayload snapshot) {
      float previousOpacity = worldPreviewOpacity;
      worldPreviewOpacity *= selectionOpacity(FastPlaceClientInput.modifierHeld());
      try { renderServerSelection(event, minecraft, player, snapshot); }
      finally { worldPreviewOpacity = previousOpacity; }
   }

   private static void renderServerSelection(RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player, OperationPreviewPayload snapshot) {
      List<BlockPos> points = snapshot.points();
      boolean closingPrismBase = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
         && snapshot.operationPrismBasePointCount() == 0
         && points.size() >= 3
         && operationPointUnderCrosshairIndex() == 0;
      SelectionPrism.EdgeInsertion edgeInsertion = closingPrismBase ? null : operationPrismEdgeInsertion();
      BlockPos candidate = closingPrismBase
         ? null
         : edgeInsertion != null
         ? edgeInsertion.point()
         : operationSelectionReady() ? null : operationCandidatePoint();
      List<BlockPos> previewPoints = new ArrayList<>(points);
      if (closingPrismBase) {
         previewPoints.add(points.getFirst());
      } else if (edgeInsertion == null && candidate != null && !previewPoints.contains(candidate)) {
         previewPoints.add(candidate);
      }

      OperationSelectionVolume selection = OperationSelectionVolume.create(
         snapshot.operationSelectionMode(),
         previewPoints,
         snapshot.operationPrismBasePointCount(),
         snapshot.operationMinOffset(),
         snapshot.operationMaxOffset(),
         snapshot.operationHullInflation()
      );
      if (snapshot.operationSelectionMode() == OperationSelectionMode.CUBOID
         && points.size() >= 2 && snapshot.selectionMin() != null && snapshot.selectionMax() != null) {
         selection = OperationSelectionVolume.cuboid(
            snapshot.selectionMin(), snapshot.selectionMax(),
            points.isEmpty() ? null : points.getFirst(), points.size() < 2 ? null : points.get(1)
         );
      }
      AABB bounds = selection == null ? null : selection.bounds();
      Vec3 eye = player.getEyePosition();
      AxisGizmo gizmo = operationGizmo();
      OperationGeometry.RayHit hit = operationFaceTarget(selection);
      OperationGeometry.RayHit guideHit = OPERATION_FACE_INTERPOLATOR.update(hit, System.nanoTime());
      float pulse = ghostBreathPulse();
      float faceAlpha = GHOST_FACE_ALPHA_MIN + (GHOST_FACE_ALPHA_MAX - GHOST_FACE_ALPHA_MIN) * pulse;
      float outlineAlpha = GHOST_OUTLINE_ALPHA_MIN + (GHOST_OUTLINE_ALPHA_MAX - GHOST_OUTLINE_ALPHA_MIN) * pulse;
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      PoseStack poseStack = event.getPoseStack();
      Vec3 camera = event.getCamera().getPosition();
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);

      if (selection != null) {
         renderOperationFaces(poseStack, buffers.getBuffer(GHOST_FACES), selection, faceAlpha, camera);
         if (hit != null) {
            renderOperationHighlightedFace(
               poseStack,
               buffers.getBuffer(GHOST_FACES),
               selection,
               hit,
               SELECTION_HIGHLIGHT_ALPHA,
               camera
            );
         }
         buffers.endBatch(GHOST_FACES);
         if (hit != null) {
            // Smooth the arrow anchor in the face plane while retaining the raycast normal.
            renderSelectionFaceNormal(
               poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES),
               guideHit == null ? hit : new OperationGeometry.RayHit(
                  guideHit.point(), hit.normal(), guideHit.distance(), hit.axis()
               ), camera, 0.82F
            );
            buffers.endBatch(GHOST_OUTLINE_LINES);
         }
      }

      VertexConsumer occludedLines = buffers.getBuffer(PENDING_XRAY_LINES);
      renderOperationSelectionPass(
         poseStack, occludedLines, snapshot, previewPoints, selection, bounds, guideHit, eye,
         1.0F, outlineAlpha
      );
      buffers.endBatch(PENDING_XRAY_LINES);

      VertexConsumer lines = buffers.getBuffer(GHOST_OUTLINE_LINES);
      renderOperationSelectionPass(
         poseStack, lines, snapshot, previewPoints, selection, bounds, guideHit, eye,
         1.0F, outlineAlpha
      );
      buffers.endBatch(GHOST_OUTLINE_LINES);

      poseStack.popPose();
      if (hit != null && !FastPlaceClientInput.modifierHeld()) {
         WorkspacePreviewRenderer.renderHintLabel(
            poseStack, buffers, minecraft, camera,
            hit.point().add(hit.normal().scale(0.08)),
            Component.translatable("fastformer.operation.face_drag_hint").getString()
         );
      }
      renderOperationPointDragGuides(poseStack, buffers, camera, snapshot);
      renderControlPoints(
         poseStack, buffers, camera, io.github.fastformer.client.controlpoint.ControlPointPresentation.selection(
            snapshot, candidate, edgeInsertion != null, operationPointUnderCrosshairIndex())
      );
      if (gizmo != null) {
         new GizmoRenderer(worldPreviewOpacity).renderGeometryGizmo(poseStack, buffers, camera, gizmo, operationGizmoAlpha(gizmo));
      }
   }

   static void renderClientOperationWorkspace(
      RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent pointerIntent
   ) {
      pointerIntent = ClientOperationController.visualHoverIntent();
      var workspace = ClientOperationController.workspace();
      var interactionScene = ClientOperationController.interactionScene();
      SmartSelectionRenderer.beginFrame(event, minecraft);
      if (minecraft.level == null || workspace.isEmpty()) {
         return;
      }
      PoseStack poseStack = event.getPoseStack();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      Vec3 camera = event.getCamera().getPosition();
      float pulse = ghostBreathPulse();
      boolean altFocused = ClientOperationController.interactionState()
         == ClientSelectionState.ALT_FOCUSED;
      float selectionOpacity = selectionOpacity(altFocused);
      OperationInteractionIntent.Gizmo hoveredGizmo = pointerIntent instanceof OperationInteractionIntent.Gizmo gizmo
         ? gizmo : null;
      OperationInteractionIntent.Face hoveredFace = pointerIntent instanceof OperationInteractionIntent.Face face
         ? face : null;
      OperationGeometry.RayHit displayedWorkspaceFaceHit = hoveredFace == null
         ? WORKSPACE_FACE_INTERPOLATOR.update(null, System.nanoTime())
         : WORKSPACE_FACE_INTERPOLATOR.update(hoveredFace.hit(), System.nanoTime());
      int hoveredPartId = hoveredGizmo != null && !hoveredGizmo.common()
         ? hoveredGizmo.partId()
         : hoveredFace != null ? hoveredFace.partId()
         : pointerIntent instanceof OperationInteractionIntent.Part part ? part.partId() : 0;
      boolean workspaceLocked = workspace.locked();
      boolean controlHeld = FastPlaceClientInput.controlHeld();

      List<ClientSelectionPart> parts = workspace.parts();
      WorkspaceInteractionResolver.pruneCache(parts);
      Map<Integer, Map<BlockPos, ClientBlockSnapshot>> resolvedParts = new LinkedHashMap<>();
      var scene = new io.github.fastformer.workspace.preview.WorkspaceScene<ClientBlockSnapshot>(snapshot -> !snapshot.state().isAir());
      for (ClientSelectionPart part : parts) {
         if (!part.pendingDelete()) {
            Map<BlockPos, ClientBlockSnapshot> resolved = WorkspaceInteractionResolver.resolveVisiblePartBlocks(part);
            resolvedParts.put(part.id(), resolved);
            scene.overlay(part.id(), resolved);
         }
      }
      // Publish every model's depth before any contour, including other parts' contours.
      for (ClientSelectionPart part : parts) {
         var interactionPart = interactionScene.parts().get(part.id());
         if (interactionPart == null || interactionPart.bounds() == null) continue;
         Map<BlockPos, ClientBlockSnapshot> resolved = part.pendingDelete()
            ? WorkspaceInteractionResolver.resolveVisiblePartBlocks(part)
            : resolvedParts.getOrDefault(part.id(), Map.of());
         Map<BlockPos, ClientBlockSnapshot> ownedBlocks = blocksOwnedByPart(resolved, scene.owners(), part.id());
         if (!shouldRenderPartBlocks(part)) {
            ownedBlocks = ownedBlocks.entrySet().stream()
               .filter(entry -> !part.pendingDelete() && io.github.fastformer.client.render.mask.SourceMaskRenderFilter.instance().hides(entry.getKey()))
               .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
         }
         if (!ownedBlocks.isEmpty()) {
            float blockAlpha = io.github.fastformer.client.render.PreviewMaterialRenderer.confirmedAlpha();
            if (altFocused) blockAlpha *= selectionOpacity;
            var capture = ClientOperationController.selectionGestures().capture();
            boolean moving = capture != null && capture.targets().containsKey(part.id());
            WorkspacePreviewRenderer.renderBlocks(poseStack, buffers, minecraft, camera, ownedBlocks, scene.blocks(),
               1.0F, 1.0F, 1.0F, blockAlpha, worldPreviewOpacity, moving);
         }
      }
      for (ClientSelectionPart part : parts) {
         Map<BlockPos, ClientBlockSnapshot> resolved = part.pendingDelete()
            ? WorkspaceInteractionResolver.resolveVisiblePartBlocks(part)
            : resolvedParts.getOrDefault(part.id(), Map.of());
         boolean selected = workspace.selectedIds().contains(part.id());
         var capture = ClientOperationController.selectionGestures().capture();
         boolean moving = capture != null && capture.targets().containsKey(part.id());
         boolean hovered = part.id() == hoveredPartId;
         var interactionPart = interactionScene.parts().get(part.id());
         AABB interactionBounds = interactionPart == null ? null : interactionPart.bounds();
         if (interactionBounds == null) {
            continue;
         }
         boolean transformGizmoVisible = io.github.fastformer.client.interaction.InteractionVisibility
            .isVisible(interactionPart.gizmo(), selected);
         Vec3 boundsCenter = interactionBounds.getCenter();
         poseStack.pushPose();
         poseStack.translate(-camera.x, -camera.y, -camera.z);
         AABB outlineBounds = interactionBounds;
         Vec3 halfExtents = new Vec3(
            outlineBounds.getXsize() * 0.5 + PreviewStyle.OUTLINE_INFLATE,
            outlineBounds.getYsize() * 0.5 + PreviewStyle.OUTLINE_INFLATE,
            outlineBounds.getZsize() * 0.5 + PreviewStyle.OUTLINE_INFLATE
         );
         if (part.smart()) {
            // The smart selection envelope is decorative and never rendered as a cuboid.
         } else if (selected && transformGizmoVisible) {
            renderConfirmedBox(
               poseStack, buffers.getBuffer(moving ? DYNAMIC_XRAY_LINES : PENDING_XRAY_LINES), outlineBounds.getCenter(), halfExtents,
               moving, selectionOpacity
            );
            renderConfirmedBox(
               poseStack, buffers.getBuffer(moving ? DYNAMIC_LINES : GHOST_OUTLINE_LINES), outlineBounds.getCenter(), halfExtents,
               moving, selectionOpacity
            );
         } else if (shouldRenderPartOutline(interactionPart, selected)) {
            LevelRenderer.renderLineBox(poseStack, buffers.getBuffer(PENDING_XRAY_LINES),
               outlineBounds.inflate(PreviewStyle.OUTLINE_INFLATE), inkRed(), inkGreen(), inkBlue(),
               (hovered ? 1.0F : 0.52F) * selectionOpacity);
               LevelRenderer.renderLineBox(
               poseStack,
               buffers.getBuffer(GHOST_OUTLINE_LINES),
               outlineBounds.inflate(PreviewStyle.OUTLINE_INFLATE),
               inkRed(),
               inkGreen(),
               inkBlue(),
               (hovered ? 1.0F : 0.52F) * selectionOpacity
            );
         }
         poseStack.popPose();
         if (part.smart()) SmartSelectionRenderer.render(event, minecraft, part, resolved,
            (selected || hovered || part.smartEditable() ? 0.9F : 0.5F) * selectionOpacity * worldPreviewOpacity);
         if (!part.smart() && !altFocused && WorkspacePartInteractionCapabilities.canSelect(part, interactionBounds)) {
            WorkspacePreviewRenderer.renderPartLabel(
               poseStack, buffers, minecraft, camera,
               interactionPart.label(),
               new PartLabelInteraction.Context(selected, ClientOperationController.hoveredLabel(interactionPart.label()),
                  workspaceLocked, controlHeld, pointerIntent, pulse)
            );
         }

         if (hoveredFace != null && hoveredFace.partId() == part.id()
            && part.editability() == ClientSelectionPart.Editability.FREE) {
            OperationSelectionVolume faceVolume = new OperationSelectionVolume(
               OperationSelectionMode.CUBOID, hoveredFace.bounds(), null, List.of(), 0, null, null
            );
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            renderOperationHighlightedFace(
               poseStack, buffers.getBuffer(GHOST_FACES), faceVolume, displayedWorkspaceFaceHit,
               (SELECTION_HIGHLIGHT_ALPHA + pulse * 0.12F) * selectionOpacity, camera
            );
            buffers.endBatch(GHOST_FACES);
            renderSelectionFaceNormal(
               poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES), displayedWorkspaceFaceHit, camera, 0.9F * selectionOpacity
            );
            buffers.endBatch(GHOST_OUTLINE_LINES);
            poseStack.popPose();
            if (!altFocused && WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
               WorkspacePreviewRenderer.renderHintLabel(
                  poseStack, buffers, minecraft, camera,
                  displayedWorkspaceFaceHit.point().add(displayedWorkspaceFaceHit.normal().scale(0.08)),
                  InteractionTooltip.summary(interactionPart.frame(), hoveredFace).orElseThrow().getString()
               );
            }
         }

         Vec3 center = boundsCenter;
         if (ClientOperationController.smartEditing()
            || !SelectionGizmoInteraction.partGizmoVisible(interactionScene, interactionPart.gizmo(), selected)) {
            continue;
         }
         GizmoViewScale scale = GizmoViewScale.fromDistance(camera.distanceTo(center));
         AxisGizmo.HandleKey hoveredKey = hoveredGizmo != null
            && !hoveredGizmo.common()
            && hoveredGizmo.partId() == part.id()
            ? hoveredGizmo.hit().handle().key() : null;
         var inputSession = FastPlaceClientInput.currentSession();
         AxisGizmo.HandleKey activeKey = PointerDragSnapshotView.workspaceGizmoMatches(inputSession, part.id(), false)
            ? PointerDragSnapshotView.operationKey(inputSession) : null;
         var gizmoGeometry = SelectionGizmoInteraction.resolvePart(interactionPart.gizmo(), scale);
         AxisGizmo worldGizmo = gizmoGeometry.world()
            .withState(hoveredKey, activeKey);
         AxisGizmo scaleGizmo = gizmoGeometry.scale()
            .withState(hoveredKey, activeKey);
         new GizmoRenderer(worldPreviewOpacity).renderWorkspaceGizmo(
            poseStack, buffers, camera, worldGizmo, scaleGizmo, (selected || hovered ? 1.0F : 0.52F) * selectionOpacity
         );
         if (PreviewGeometrySupport.hasNonOrthogonalRotation(part.transform().rotation())) {
            AxisGizmo.Axis highlightedLocalAxis = hoveredGizmo != null
               && !hoveredGizmo.common()
               && hoveredGizmo.partId() == part.id()
               && hoveredGizmo.hit().handle().operation() == AxisGizmo.Operation.SCALE
               ? hoveredGizmo.hit().handle().axis()
               : activeKey != null && activeKey.operation() == AxisGizmo.Operation.SCALE
                  ? activeKey.axis() : null;
            new GizmoRenderer(worldPreviewOpacity).renderLocalWorkspaceGizmo(
               poseStack, buffers, camera, center, scale.axisLength() * 0.82,
               part.transform().rotation(), highlightedLocalAxis, (hovered ? 1.0F : 0.58F) * selectionOpacity
            );
         }
      }

      var groupObject = interactionScene.groupGizmo();
      if (groupObject != null && !ClientOperationController.smartEditing()) {
         AABB groupBounds = groupObject.require(InteractionComponents.WORLD_BOUNDS);
         if (groupBounds != null) {
            Vec3 center = groupBounds.getCenter();
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            renderConfirmedBox(
               poseStack,
               buffers.getBuffer(ClientOperationController.selectionGestures().active() ? DYNAMIC_LINES : GHOST_OUTLINE_LINES),
               center,
               new Vec3(
                  groupBounds.getXsize() * 0.5 + 3.0 * PreviewStyle.OUTLINE_INFLATE,
                  groupBounds.getYsize() * 0.5 + 3.0 * PreviewStyle.OUTLINE_INFLATE,
                  groupBounds.getZsize() * 0.5 + 3.0 * PreviewStyle.OUTLINE_INFLATE
               ),
               ClientOperationController.selectionGestures().active(),
               0.96F * selectionOpacity
            );
            poseStack.popPose();
            GizmoViewScale scale = GizmoViewScale.fromDistance(camera.distanceTo(center));
            AxisGizmo common = SelectionGizmoInteraction.resolveGroup(groupObject, scale);
            AxisGizmo.HandleKey hoveredKey = hoveredGizmo != null && hoveredGizmo.common()
               ? hoveredGizmo.hit().handle().key() : null;
            var inputSession = FastPlaceClientInput.currentSession();
            AxisGizmo.HandleKey activeKey = PointerDragSnapshotView.workspaceGizmoMatches(inputSession, 0, true)
               ? PointerDragSnapshotView.operationKey(inputSession) : null;
            common = common.withState(hoveredKey, activeKey);
            new GizmoRenderer(worldPreviewOpacity).renderGeometryGizmo(poseStack, buffers, camera, common, selectionOpacity);
            if (!altFocused) WorkspacePreviewRenderer.renderHintLabel(poseStack, buffers, minecraft, camera,
               center.add(0, common.axisLength() * 1.55, 0),
               Component.translatable("fastformer.gizmo.group", workspace.selectedIds().size()).getString());
         }
      }
      buffers.endBatch(GHOST_OUTLINE_LINES);
      buffers.endBatch(DYNAMIC_LINES);
      buffers.endBatch(DYNAMIC_XRAY_LINES);
   }

   static boolean shouldRenderPartBlocks(ClientSelectionPart part) {
      return part != null && !part.pendingDelete()
         && (part.source() == ClientSelectionPart.Source.CLIPBOARD || part.masksSourceBlocks());
   }

   static boolean shouldRenderPartOutline(io.github.fastformer.client.interaction.SelectionInteractionScene.Part part, boolean selected) {
      return part != null && part.bounds() != null
         && io.github.fastformer.client.interaction.InteractionVisibility.isVisible(part.frame(), selected);
   }

   /**
    * The server-owned selection stays visible while Alt changes its input
    * meaning. Alt can create another selection, but it must not hide the
    * current selection before that click creates a local draft.
    */
   static boolean shouldRenderServerSelection(
      boolean operationPreviewActive, boolean workspaceActive, boolean selectionDraftActive
   ) {
      return operationPreviewActive && !workspaceActive && !selectionDraftActive;
   }

   static <T> Map<BlockPos, T> blocksOwnedByPart(
      Map<BlockPos, T> blocks, Map<BlockPos, Integer> owners, int partId
   ) {
      if (blocks == null || blocks.isEmpty() || owners == null || owners.isEmpty()) {
         return Map.of();
      }
      LinkedHashMap<BlockPos, T> owned = new LinkedHashMap<>();
      blocks.forEach((pos, value) -> {
         if (Objects.equals(owners.get(pos), partId)) {
            owned.put(pos, value);
         }
      });
      return Map.copyOf(owned);
   }

   /** Draws the shared marker for every state where left/right creates a selection point. */
   static void renderSelectionCreationCandidate(
      RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent pointerIntent
   ) {
      if (io.github.fastformer.client.interaction.SmartSelectionEditView.active(minecraft)) return;
      if (!(pointerIntent instanceof OperationInteractionIntent.CreateSelection create)
         || minecraft.level == null || minecraft.player == null) {
         return;
      }
      BlockPos position = create.point();
      PoseStack poseStack = event.getPoseStack();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      Vec3 camera = event.getCamera().getPosition();
      float pulse = ghostBreathPulse();
      var state = minecraft.level.getBlockState(position);
      var shape = state.isAir() ? net.minecraft.world.phys.shapes.Shapes.block()
         : io.github.fastformer.fastplace.geometry.raycast.SelectionTargetShape.resolve(minecraft.level, position,
            net.minecraft.world.phys.shapes.CollisionContext.of(minecraft.player), FastPlaceClientInput.modifierHeld());
      var mesh = io.github.fastformer.client.render.model.InitialBlockPreview.mesh(position, shape.toAabbs());
      io.github.fastformer.client.render.shell.ShapeShellRenderer.renderDashedEdges(
         poseStack, buffers.getBuffer(DYNAMIC_LINES), camera, mesh.edges(),
         GHOST_OUTLINE_ALPHA_MIN + 0.15F * pulse, pendingGridDashOffset()
      );
      buffers.endBatch(DYNAMIC_LINES);
      // A locked workspace refuses the creation click, so the marker keeps its
      // position but drops the command text.
      if (WorkspacePointerPrompt.acceptsNewAction(ClientOperationController.workspace().locked())) {
         WorkspacePreviewRenderer.renderHintLabel(
            poseStack, buffers, minecraft, camera, Vec3.atCenterOf(position).add(0.0, 0.68, 0.0),
            ClientOperationController.smartTool()
               ? Component.translatable(FastPlaceClientInput.modifierHeld()
                  ? "fastformer.hud.smart_hover_refine" : "fastformer.hud.smart_hover").getString()
               : create.requireHoverText().getString()
         );
      }
   }

   private static void renderOperationPointDragGuides(
      PoseStack poseStack, BufferSource buffers, Vec3 camera, OperationPreviewPayload snapshot
   ) {
      var inputSession = FastPlaceClientInput.currentSession();
      SelectionPrism.GridPlane plane = PointerDragSnapshotView.pointPlane(inputSession);
      SelectionPrism.GridLine line = PointerDragSnapshotView.pointLine(inputSession);
      if (plane == null && line == null) {
         return;
      }
      VertexConsumer consumer = buffers.getBuffer(GHOST_OUTLINE_LINES);
      if (plane != null) {
         List<BlockPos> guidePoints = snapshot.operationSelectionMode() == OperationSelectionMode.CUBOID
            && snapshot.selectionMin() != null && snapshot.selectionMax() != null
            ? List.of(snapshot.selectionMin(), snapshot.selectionMax())
            : snapshot.points();
         List<Vec3> bounds = new ArrayList<>(guidePoints.stream().map(Vec3::atCenterOf).toList());
         BlockPos target = PointerDragSnapshotView.pointTarget(inputSession);
         if (target != null) {
            bounds.add(Vec3.atCenterOf(target));
         }
         renderGuidePlaneGrid(
            poseStack, consumer, camera, List.of(new GuidePlane(plane.anchor(), plane.normal(), bounds, true))
         );
      }
      if (line != null) {
         Vec3 anchor = line.anchor();
         double radius = Math.max(8.0, camera.distanceTo(anchor) * 0.3);
         BlockPos target = PointerDragSnapshotView.pointTarget(inputSession);
         if (target != null) {
            radius = Math.max(radius, Math.abs(Vec3.atCenterOf(target).subtract(anchor).dot(line.direction())) + 4.0);
         }
         renderGuideLines(
            poseStack,
            consumer,
            camera,
            List.of(new GuideLine(anchor.subtract(line.direction().scale(radius)), anchor.add(line.direction().scale(radius))))
         );
      }
      buffers.endBatch(GHOST_OUTLINE_LINES);
   }

   private static void renderOperationSelectionPass(
      PoseStack poseStack,
      VertexConsumer lines,
      OperationPreviewPayload snapshot,
      List<BlockPos> points,
      OperationSelectionVolume selection,
      AABB bounds,
      OperationGeometry.RayHit hit,
      Vec3 eye,
      float alphaScale,
      float outlineAlpha
   ) {
      if (selection != null) {
         renderOperationVolume(poseStack, lines, selection, Vec3.ZERO, outlineAlpha * alphaScale);
         // The volume's outline is the authoritative world-space boundary.
         // Do not overlay point-derived edges while a transformed selection is
         // active; those edges use pre-transform anchors and can visibly split
         // from the filled preview after rotation.
      } else if (points.size() >= 2) {
         if (snapshot.operationSelectionMode() == OperationSelectionMode.PRISM) {
            int baseCount = snapshot.operationPrismBasePointCount() > 0
               ? snapshot.operationPrismBasePointCount()
               : points.size();
            for (int i = 1; i < baseCount; i++) {
               renderOperationOutlineLine(
                  poseStack, lines, Vec3.atCenterOf(points.get(i - 1)), Vec3.atCenterOf(points.get(i)),
                  outlineAlpha * alphaScale
               );
            }
            if (snapshot.operationPrismBasePointCount() >= 3) {
               renderOperationOutlineLine(
                  poseStack, lines, Vec3.atCenterOf(points.get(baseCount - 1)), Vec3.atCenterOf(points.getFirst()),
                  outlineAlpha * alphaScale
               );
            }
         } else {
            Vec3 anchor = Vec3.atCenterOf(points.getFirst());
            for (int i = 1; i < points.size(); i++) {
               renderOperationOutlineLine(poseStack, lines, anchor, Vec3.atCenterOf(points.get(i)), outlineAlpha * alphaScale);
            }
         }
      }
      if (snapshot.operationSelectionMode() == OperationSelectionMode.CONVEX_HULL) {
         for (OperationGeometry.HullFace face : OperationGeometry.convexHullFaces(points)) {
            renderOperationOutlineLine(poseStack, lines, face.a(), face.b(), outlineAlpha * alphaScale);
            renderOperationOutlineLine(poseStack, lines, face.b(), face.c(), outlineAlpha * alphaScale);
            renderOperationOutlineLine(poseStack, lines, face.c(), face.a(), outlineAlpha * alphaScale);
         }
      }
      if (hit != null) {
         double length = Math.max(2.0, eye.distanceTo(hit.point()) * 0.08);
         renderStaticOperationGuideLine(
            poseStack,
            lines,
            hit.point(),
            hit.point().add(hit.normal().scale(length)),
            0.78F * alphaScale
         );
      }

      if (selection != null && snapshot.operationAdjustmentStarted()) {
         for (BlockPos repetition : OperationGizmoPresentation.stackOffsets(
            snapshot.operationStackMin(), snapshot.operationStackMax(), 512
         )) {
            BlockPos displacement = OperationGeometry.stackDisplacement(bounds, repetition)
               .offset(snapshot.operationTranslation());
            if (displacement.equals(BlockPos.ZERO)) {
               continue;
            }
            renderOperationVolume(
               poseStack, lines, selection,
               Vec3.atLowerCornerOf(displacement),
               outlineAlpha * 0.78F * alphaScale
            );
         }
      }
   }

   private static void renderOperationFaces(
      PoseStack poseStack, VertexConsumer consumer, OperationSelectionVolume selection, float alpha, Vec3 camera
   ) {
      float red = inkRed();
      float green = inkGreen();
      float blue = inkBlue();
      if (selection.prism() != null) {
         List<Vec3> base = selection.prism().base();
         Vec3 extrusion = selection.prism().extrusion();
         Vec3 center = prismFaceCenter(base, extrusion);
         if (center == null) {
            return;
         }
         for (int index = 1; index + 1 < base.size(); index++) {
            Vec3 a = inflateSelectionVertex(base.getFirst(), center, camera);
            Vec3 b = inflateSelectionVertex(base.get(index), center, camera);
            Vec3 c = inflateSelectionVertex(base.get(index + 1), center, camera);
            addGhostQuad(poseStack, consumer, a, b, c, c, red, green, blue, alpha);
            addGhostQuad(
               poseStack, consumer,
               inflateSelectionVertex(base.getFirst().add(extrusion), center, camera),
               inflateSelectionVertex(base.get(index + 1).add(extrusion), center, camera),
               inflateSelectionVertex(base.get(index).add(extrusion), center, camera),
               inflateSelectionVertex(base.get(index).add(extrusion), center, camera),
               red, green, blue, alpha
            );
         }
         for (int index = 0; index < base.size(); index++) {
            int next = (index + 1) % base.size();
            Vec3 a = inflateSelectionVertex(base.get(index), center, camera);
            Vec3 b = inflateSelectionVertex(base.get(next), center, camera);
            Vec3 c = inflateSelectionVertex(base.get(next).add(extrusion), center, camera);
            Vec3 d = inflateSelectionVertex(base.get(index).add(extrusion), center, camera);
            addGhostQuad(poseStack, consumer, a, b, c, d, red, green, blue, alpha);
         }
         return;
      }

      AABB box = selection.bounds().inflate(SELECTION_FACE_INFLATE);
      Vec3 center = box.getCenter();
      Vec3 p000 = inflateSelectionVertex(new Vec3(box.minX, box.minY, box.minZ), center, camera);
      Vec3 p100 = inflateSelectionVertex(new Vec3(box.maxX, box.minY, box.minZ), center, camera);
      Vec3 p110 = inflateSelectionVertex(new Vec3(box.maxX, box.maxY, box.minZ), center, camera);
      Vec3 p010 = inflateSelectionVertex(new Vec3(box.minX, box.maxY, box.minZ), center, camera);
      Vec3 p001 = inflateSelectionVertex(new Vec3(box.minX, box.minY, box.maxZ), center, camera);
      Vec3 p101 = inflateSelectionVertex(new Vec3(box.maxX, box.minY, box.maxZ), center, camera);
      Vec3 p111 = inflateSelectionVertex(new Vec3(box.maxX, box.maxY, box.maxZ), center, camera);
      Vec3 p011 = inflateSelectionVertex(new Vec3(box.minX, box.maxY, box.maxZ), center, camera);
      addGhostQuad(poseStack, consumer, p000, p100, p110, p010, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p001, p011, p111, p101, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p000, p001, p101, p100, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p010, p110, p111, p011, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p000, p010, p011, p001, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p100, p101, p111, p110, red, green, blue, alpha);
   }

   /**
    * Centroid of a prism base plus half its extrusion. Returns null when the base
    * cannot form a face: the triangle loop needs three points, and dividing by a
    * smaller base size would not be finite.
    */
   static Vec3 prismFaceCenter(List<Vec3> base, Vec3 extrusion) {
      if (base == null || base.size() < 3) {
         return null;
      }
      return base.stream().reduce(Vec3.ZERO, Vec3::add)
         .scale(1.0 / base.size())
         .add(extrusion.scale(0.5));
   }

   private static void renderOperationHighlightedFace(
      PoseStack poseStack,
      VertexConsumer consumer,
      OperationSelectionVolume selection,
      OperationGeometry.RayHit hit,
      float alpha,
      Vec3 camera
   ) {
      float red = inkRed();
      float green = inkGreen();
      float blue = inkBlue();
      if (selection.prism() != null) {
         List<Vec3> base = selection.prism().base();
         Vec3 extrusion = selection.prism().extrusion();
         // Every branch below indexes the base and wraps with a modulo, so a
         // shorter base has no addressable side face.
         if (base.size() < 3) {
            return;
         }
         if (hit.axis() == 2) {
            boolean top = hit.normal().dot(extrusion) > 0.0;
            Vec3 offset = top ? extrusion : Vec3.ZERO;
            for (int index = 1; index + 1 < base.size(); index++) {
               Vec3 bias = hit.normal().scale(SELECTION_FACE_INFLATE * 0.35);
               Vec3 a = base.getFirst().add(offset).add(bias);
               Vec3 b = base.get(top ? index + 1 : index).add(offset).add(bias);
               Vec3 c = base.get(top ? index : index + 1).add(offset).add(bias);
               addGhostQuad(poseStack, consumer, a, b, c, c, red, green, blue, alpha);
            }
         } else if (hit.axis() >= 3) {
            int index = hit.axis() - 3;
            if (index < base.size()) {
               Vec3 bias = hit.normal().scale(SELECTION_FACE_INFLATE * 0.35);
               Vec3 a = base.get(index).add(bias);
               Vec3 b = base.get((index + 1) % base.size()).add(bias);
               addGhostQuad(poseStack, consumer, a, b, b.add(extrusion), a.add(extrusion), red, green, blue, alpha);
            }
         }
         return;
      }

      AABB box = selection.bounds();
      boolean positive = hit.normal().dot(selection.axis(hit.axis())) > 0.0;
      double coordinate = switch (hit.axis()) {
         case 0 -> positive ? box.maxX : box.minX;
         case 1 -> positive ? box.maxY : box.minY;
         default -> positive ? box.maxZ : box.minZ;
      };
      Vec3 a;
      Vec3 b;
      Vec3 c;
      Vec3 d;
      if (hit.axis() == 0) {
         a = new Vec3(coordinate, box.minY, box.minZ);
         b = new Vec3(coordinate, box.maxY, box.minZ);
         c = new Vec3(coordinate, box.maxY, box.maxZ);
         d = new Vec3(coordinate, box.minY, box.maxZ);
      } else if (hit.axis() == 1) {
         a = new Vec3(box.minX, coordinate, box.minZ);
         b = new Vec3(box.minX, coordinate, box.maxZ);
         c = new Vec3(box.maxX, coordinate, box.maxZ);
         d = new Vec3(box.maxX, coordinate, box.minZ);
      } else {
         a = new Vec3(box.minX, box.minY, coordinate);
         b = new Vec3(box.maxX, box.minY, coordinate);
         c = new Vec3(box.maxX, box.maxY, coordinate);
         d = new Vec3(box.minX, box.maxY, coordinate);
      }
      // The face is the same plane as the inflated outline, translated only
      // along its normal. Do not bias each vertex toward the camera: that
      // bends the face and makes it diverge from its outline.
      Vec3 bias = hit.normal().scale(SELECTION_FACE_INFLATE * 0.35);
      a = a.add(bias);
      b = b.add(bias);
      c = c.add(bias);
      d = d.add(bias);
      addGhostQuad(poseStack, consumer, a, b, c, d, red, green, blue, alpha);
   }

   private static void renderSelectionFaceNormal(
      PoseStack poseStack, VertexConsumer consumer, OperationGeometry.RayHit hit, Vec3 camera, float alpha
   ) {
      if (hit == null || hit.normal().lengthSqr() < EPSILON) {
         return;
      }
      Vec3 normal = hit.normal().normalize();
      Vec3 from = hit.point().add(normal.scale(SELECTION_FACE_INFLATE * 2.5));
      Vec3 to = from.add(normal.scale(0.62));
      Vec3 side = normal.cross(Math.abs(normal.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(0.08);
      Vec3 base = to.subtract(normal.scale(0.14));
      renderLine(poseStack, consumer, from, to, inkRed(), inkGreen(), inkBlue(), alpha);
      renderLine(poseStack, consumer, to, base.add(side), inkRed(), inkGreen(), inkBlue(), alpha);
      renderLine(poseStack, consumer, to, base.subtract(side), inkRed(), inkGreen(), inkBlue(), alpha);
   }

   private static Vec3 inflateSelectionVertex(Vec3 point, Vec3 center, Vec3 camera) {
      // Faces and outlines must share the same rigid geometry. Polygon offset
      // on GHOST_FACES supplies depth separation; radial/camera displacement
      // would make the face no longer be a translated copy of its outline.
      return point;
   }

   private static Vec3 cameraBiasedSelectionVertex(Vec3 point, Vec3 camera) {
      Vec3 towardCamera = camera.subtract(point);
      if (towardCamera.lengthSqr() < EPSILON) {
         return point;
      }
      double distance = Math.sqrt(towardCamera.lengthSqr());
      double bias = Math.min(0.12, SELECTION_FACE_INFLATE + distance * 0.00005);
      return point.add(towardCamera.scale(bias / distance));
   }

}
