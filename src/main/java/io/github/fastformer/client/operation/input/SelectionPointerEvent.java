package io.github.fastformer.client.operation.input;

import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.gesture.PathClosePress;
import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import java.util.Objects;
import java.util.UUID;

/** Physical pointer events keep their identity until the corresponding edit settles. */
public sealed interface SelectionPointerEvent {
   public long identity();

   /** Frozen server preview and view-derived drag frame addressed by a queued press. */
   record RemoteSnapshot(UUID owner, OperationDraftIdentity selection, long revision, OperationCallbackScope scope,
      int button, OperationDrag drag, GeometryGizmoDrag gizmo, PathClosePress path, boolean undo) {
      public RemoteSnapshot {
         Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(scope, "scope");
         if (button < 0) throw new IllegalArgumentException("Remote selection press requires a physical button");
      }
      public boolean matches(UUID currentOwner, OperationDraftIdentity currentSelection,
         long currentRevision, OperationCallbackScope currentScope) {
         return owner.equals(currentOwner) && Objects.equals(selection, currentSelection)
            && revision == currentRevision && scope.equals(currentScope);
      }
      public boolean ownsActive(UUID currentOwner, OperationCallbackScope currentScope) {
         return owner.equals(currentOwner) && scope.equals(currentScope);
      }
   }

   public record Press(long identity, SelectionPointerPress snapshot, boolean alt) implements SelectionPointerEvent {
      public Press {
         if (identity <= 0 || snapshot == null) {
            throw new IllegalArgumentException("A selection press requires a positive identity and a target snapshot");
         }
      }
   }
   public record CreatePress(long identity, SelectionDraftPress snapshot) implements SelectionPointerEvent {
      public CreatePress {
         if (identity <= 0 || snapshot == null) {
            throw new IllegalArgumentException("A draft press requires a positive identity and a snapshot");
         }
      }
   }
   public record PointPress(long identity, SelectionPointPress snapshot) implements SelectionPointerEvent {
      public PointPress {
         if (identity <= 0 || snapshot == null) {
            throw new IllegalArgumentException("A point press requires a positive identity and a snapshot");
         }
      }
   }
   public record Release(long identity, int button, long occurredAtNanos) implements SelectionPointerEvent {
      public Release {
         if (identity <= 0 || button < 0) {
            throw new IllegalArgumentException("A selection release requires a positive identity and a supported button");
         }
      }
   }

   public record RemotePress(long identity,
      RemoteSnapshot snapshot) implements SelectionPointerEvent {
      public RemotePress {
         if (identity <= 0 || snapshot == null) throw new IllegalArgumentException("Remote press requires identity and snapshot");
      }
   }

   public record Cancel(long identity) implements SelectionPointerEvent {
      public Cancel {
         if (identity <= 0) throw new IllegalArgumentException("A selection cancel requires a positive identity");
      }
   }
}
