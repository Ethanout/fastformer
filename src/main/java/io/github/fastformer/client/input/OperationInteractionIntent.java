package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** The single resolved meaning of the operation pointer for input and presentation. */
public sealed interface OperationInteractionIntent {
   Action action();

   TargetKey key();

   enum Action {
      DRAG_GIZMO,
      ADJUST_FACE,
      SELECT_PART,
      CREATE_SELECTION
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
   }

   record Part(int partId, double distance) implements OperationInteractionIntent {
      @Override
      public Action action() {
         return Action.SELECT_PART;
      }

      @Override
      public TargetKey key() {
         return new TargetKey("part", this.partId, 0);
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
   }
}
