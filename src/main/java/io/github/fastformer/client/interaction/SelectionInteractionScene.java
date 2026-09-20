package io.github.fastformer.client.interaction;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.phys.AABB;

/** A complete, immutable scene published by the selection session after an edit. */
public record SelectionInteractionScene(UUID owner, Map<Integer, Part> parts, InteractionObject groupGizmo) {
   public SelectionInteractionScene {
      Objects.requireNonNull(owner, "owner");
      parts = Collections.unmodifiableMap(new LinkedHashMap<>(parts));
   }

   public static SelectionInteractionScene empty(UUID owner) {
      return new SelectionInteractionScene(owner, Map.of(), null);
   }

   public AABB bounds(int partId) {
      Part part = this.parts.get(partId);
      return part == null ? null : part.bounds();
   }

   public InteractionObject object(InteractionObject.Id id) {
      if (id == null || !this.owner.equals(id.session())) return null;
      if (this.groupGizmo != null && this.groupGizmo.id().equals(id)) return this.groupGizmo;
      for (Part part : this.parts.values()) {
         if (part.label() != null && part.label().id().equals(id)) return part.label();
         if (part.frame() != null && part.frame().id().equals(id)) return part.frame();
         if (part.gizmo() != null && part.gizmo().id().equals(id)) return part.gizmo();
      }
      return null;
   }

   public InteractionObject targetObject(OperationInteractionIntent intent) {
      int partId = switch (intent) {
         case OperationInteractionIntent.Part part -> part.partId();
         case OperationInteractionIntent.Face face -> face.partId();
         case OperationInteractionIntent.Gizmo gizmo -> gizmo.partId();
         case null, default -> 0;
      };
      if (intent instanceof OperationInteractionIntent.Gizmo gizmo && gizmo.common()) return this.groupGizmo;
      Part part = this.parts.get(partId);
      if (part == null) return null;
      return switch (intent) {
         case OperationInteractionIntent.Part hit -> hit.surface() == OperationInteractionIntent.PartSurface.FRAME
            ? part.frame() : part.label();
         case OperationInteractionIntent.Face face -> part.frame();
         case OperationInteractionIntent.Gizmo gizmo -> part.gizmo();
         case null, default -> null;
      };
   }

   public static SelectionInteractionScene capture(
      UUID owner, ClientOperationWorkspace workspace, SelectionInteractionScene previous
   ) {
      boolean sameOwner = previous != null && previous.owner().equals(owner);
      boolean unchanged = sameOwner && previous.parts().size() == workspace.size();
      Map<Integer, Part> next = new LinkedHashMap<>();
      for (ClientSelectionPart source : workspace.parts()) {
         long identity = workspace.interactionId(source.id());
         Part existing = sameOwner ? previous.parts().get(source.id()) : null;
         Part part = existing != null && existing.source() == source && existing.identity() == identity
            ? existing : createPart(owner, identity, source);
         unchanged &= existing == part;
         next.put(source.id(), part);
      }
      var group = SelectionGizmoInteraction.captureGroup(owner, next, workspace.selectedIds(), sameOwner ? previous.groupGizmo() : null);
      unchanged &= sameOwner && group == previous.groupGizmo();
      return unchanged ? previous : new SelectionInteractionScene(owner, next, group);
   }

   private static Part createPart(UUID owner, long identity, ClientSelectionPart source) {
      AABB bounds = PartInteractionBounds.resolve(source);
      var role = InteractionComponents.SelectionRole.from(source);
      InteractionObject label = bounds == null ? null : PartLabelInteraction.create(owner, identity, source.id(), bounds, role);
      InteractionObject frame = bounds == null ? null : PartFrameInteraction.create(owner, identity, bounds, role);
      InteractionObject gizmo = bounds == null ? null : SelectionGizmoInteraction.createPart(owner, identity, source, bounds.getCenter(), role);
      return new Part(source, identity, frame, label, gizmo);
   }

   public record Part(ClientSelectionPart source, long identity, InteractionObject frame, InteractionObject label, InteractionObject gizmo) {
      public AABB bounds() {
         return this.frame == null ? null : this.frame.require(InteractionComponents.WORLD_BOUNDS);
      }
   }
}
