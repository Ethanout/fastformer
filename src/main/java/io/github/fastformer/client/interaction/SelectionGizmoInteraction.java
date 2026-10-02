package io.github.fastformer.client.interaction;

import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.fastplace.geometry.text.GizmoTextComponent;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared world-space geometry for selection gizmo rendering and picking. */
public final class SelectionGizmoInteraction {
   private SelectionGizmoInteraction() { }

   public record Frames(TransformFrame world, TransformFrame localScale) {
      public Frames {
         Objects.requireNonNull(world, "world");
         Objects.requireNonNull(localScale, "localScale");
      }
   }

   public record PartGeometry(AxisGizmo world, AxisGizmo scale) {
      public List<AxisGizmo> gizmos() { return List.of(world, scale); }
   }

   public static InteractionObject createPart(UUID owner, long identity, ClientSelectionPart part, Vec3 center) {
      return createPart(owner, identity, part, center, InteractionComponents.SelectionRole.from(part));
   }

   public static InteractionObject createPart(UUID owner, long identity, ClientSelectionPart part, Vec3 center,
      InteractionComponents.SelectionRole role) {
      TransformFrame world = TransformFrame.world(center);
      Vec3 rotation = part.transform().rotation();
      TransformFrame local = rotation.equals(Vec3.ZERO) ? world : TransformFrame.local(
         center,
         PreviewGeometrySupport.rotateLocalAxis(AxisGizmo.Axis.X, rotation),
         PreviewGeometrySupport.rotateLocalAxis(AxisGizmo.Axis.Y, rotation),
         PreviewGeometrySupport.rotateLocalAxis(AxisGizmo.Axis.Z, rotation)
      );
      return InteractionObject.builder(new InteractionObject.Id(owner, "part_gizmo", identity))
         .with(InteractionComponents.GIZMO_FRAMES, new Frames(world, local))
         .with(InteractionComponents.VISIBILITY, partVisibility(part))
         .with(InteractionComponents.SELECTION_ROLE, role)
         .with(InteractionComponents.TOOLTIP, InteractionTooltip.GIZMO)
         .with(InteractionComponents.PRESS_BINDING, InteractionPressBinding.GIZMO)
         .build();
   }

   public static PartGeometry resolvePart(InteractionObject object, GizmoViewScale scale) {
      Frames frames = object.require(InteractionComponents.GIZMO_FRAMES);
      return new PartGeometry(
         AxisGizmo.inFrame(frames.world(), scale.axisLength(), scale.handleRadius(),
            AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE).withTextComponent(GizmoTextComponent.pointLevel()),
         AxisGizmo.inFrame(frames.localScale(), scale.axisLength(), scale.handleRadius(),
            AxisGizmo.Operation.SCALE).withTextComponent(GizmoTextComponent.pointLevel())
      );
   }

   /** A multiple selection has one transform owner, shared by rendering and picking. */
   public static boolean partGizmoVisible(SelectionInteractionScene scene, InteractionObject object, boolean selected) {
      return !scene.smartEditing() && scene.groupGizmo() == null && InteractionVisibility.isVisible(object, selected);
   }

   private static InteractionVisibility partVisibility(ClientSelectionPart part) {
      if (part.editability() == ClientSelectionPart.Editability.FREE) return InteractionVisibility.ALWAYS;
      return InteractionVisibility.WHEN_SELECTED;
   }

   public record Group(Map<Integer, Long> members, boolean scaleEnabled) {
      public Group { members = Map.copyOf(members); }
   }

   public static InteractionObject captureGroup(UUID owner, Map<Integer, SelectionInteractionScene.Part> parts,
      Set<Integer> selected, InteractionObject previous) {
      if (selected.size() < 2 || parts.values().stream().anyMatch(part -> part.source().smartEditable())) return null;
      AABB bounds = null;
      boolean scaleEnabled = true;
      Map<Integer, Long> members = new LinkedHashMap<>();
      for (int id : selected) {
         var part = parts.get(id);
         if (part == null) return null;
         members.put(id, part.identity());
         scaleEnabled &= part.source().selection() == null || part.source().selection().prism() == null;
         AABB next = part.bounds();
         if (next != null) bounds = bounds == null ? next : bounds.minmax(next);
      }
      if (bounds == null) return null;
      var id = new InteractionObject.Id(owner, "selection_gizmo", 1);
      var group = new Group(members, scaleEnabled);
      if (previous != null && previous.id().equals(id)
         && bounds.equals(previous.require(InteractionComponents.WORLD_BOUNDS))
         && group.equals(previous.require(InteractionComponents.GROUP_GIZMO))) return previous;
      return InteractionObject.builder(id)
         .with(InteractionComponents.WORLD_BOUNDS, bounds)
         .with(InteractionComponents.TOOLTIP, InteractionTooltip.GIZMO)
         .with(InteractionComponents.PRESS_BINDING, InteractionPressBinding.GIZMO)
         .with(InteractionComponents.GROUP_GIZMO, group).build();
   }

   public static AxisGizmo resolveGroup(InteractionObject object, GizmoViewScale scale) {
      AABB bounds = object.require(InteractionComponents.WORLD_BOUNDS);
      AxisGizmo.Operation[] operations = object.require(InteractionComponents.GROUP_GIZMO).scaleEnabled()
         ? new AxisGizmo.Operation[] {AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE}
         : new AxisGizmo.Operation[] {AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE};
      return AxisGizmo.inFrame(TransformFrame.world(bounds.getCenter()),
         scale.axisLength() * 1.35, scale.handleRadius() * 1.35, operations)
         .withTextComponent(GizmoTextComponent.pointLevel());
   }
}
