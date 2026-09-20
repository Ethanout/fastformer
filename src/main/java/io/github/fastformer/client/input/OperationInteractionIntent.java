package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Optional;
import io.github.fastformer.client.interaction.InteractionTooltip;

/** The single resolved meaning of the operation pointer for input and presentation. */
public sealed interface OperationInteractionIntent {
   Action action();

   TargetKey key();

   /** Whether this resolved target may accept its action right now. */
   default Availability availability() {
      return Availability.AVAILABLE;
   }

   /** A user-facing explanation when the target is unavailable. */
   default Optional<Component> unavailableReason() {
      return Optional.empty();
   }

   /** Text shown beside the crosshair while this resolved target is hovered. */
   default Optional<Component> hoverText() {
      return Optional.empty();
   }

   /** All crosshair lines for this target, in display order. */
   default List<Component> hoverTextLines() {
      return hoverText().map(List::of).orElseGet(List::of);
   }

   /** Returns the configured hover text and fails loudly for an incomplete target. */
   default Component requireHoverText() {
      return hoverText().orElseThrow(() -> new IllegalStateException(
         "Operation interaction target is missing hover text: " + getClass().getName()));
   }

   /** Returns all configured lines and fails loudly for an incomplete target. */
   default List<Component> requireHoverTextLines() {
      List<Component> lines = hoverTextLines();
      if (lines.isEmpty() || lines.stream().anyMatch(java.util.Objects::isNull)) {
         throw new IllegalStateException(
            "Operation interaction target is missing hover text: " + getClass().getName());
      }
      return List.copyOf(lines);
   }

   enum Action {
      DRAG_GIZMO,
      ADJUST_FACE,
      SELECT_PART,
      CREATE_SELECTION
   }

   enum Availability {
      AVAILABLE,
      UNAVAILABLE
   }

   record TargetKey(String kind, int owner, int detail) {
   }

   record Gizmo(int partId, boolean common, AxisGizmo gizmo, AxisGizmo.Hit hit)
      implements OperationInteractionIntent {
      @Override
      public Action action() {
         return Action.DRAG_GIZMO;
      }

      @Override
      public TargetKey key() {
         return new TargetKey("gizmo", this.common ? 0 : this.partId, this.hit.handle().key().hashCode());
      }

      @Override
      public Optional<Component> hoverText() {
         return InteractionTooltip.GIZMO.summary(this);
      }
   }

   record Face(int partId, AABB bounds, OperationGeometry.RayHit hit, boolean adjustable)
      implements OperationInteractionIntent {
      @Override
      public Action action() {
         return Action.ADJUST_FACE;
      }

      @Override
      public TargetKey key() {
         int direction = this.hit.normal().x < 0.0 || this.hit.normal().y < 0.0 || this.hit.normal().z < 0.0 ? -1 : 1;
         return new TargetKey("face", this.partId, this.hit.axis() * 2 + (direction > 0 ? 1 : 0));
      }

      @Override
      public Optional<Component> hoverText() {
         return InteractionTooltip.FRAME.summary(this);
      }

      @Override
      public List<Component> hoverTextLines() {
         return InteractionTooltip.FRAME.lines(this);
      }
   }

   enum PartSurface { LABEL, FRAME }

   record Part(int partId, double distance, PartSurface surface) implements OperationInteractionIntent {
      public Part(int partId, double distance) {
         this(partId, distance, PartSurface.LABEL);
      }

      public Part {
         java.util.Objects.requireNonNull(surface, "surface");
      }
      @Override
      public Action action() {
         return Action.SELECT_PART;
      }

      @Override
      public TargetKey key() {
         return new TargetKey("part", this.partId, 0);
      }

      @Override
      public Optional<Component> hoverText() {
         return InteractionTooltip.SELECTION.summary(this);
      }
   }

   record CreateSelection(BlockPos point) implements OperationInteractionIntent {
      public CreateSelection {
         point = point.immutable();
      }

      @Override
      public Action action() {
         return Action.CREATE_SELECTION;
      }

      @Override
      public TargetKey key() {
         return new TargetKey("selection-create", 0, this.point.hashCode());
      }

      @Override
      public Optional<Component> hoverText() {
         return Optional.of(Component.translatable("fastformer.operation.selection_create_hint"));
      }
   }
}
