package io.github.fastformer.client.operation.input;

/** Physical pointer events keep their identity until the corresponding edit settles. */
public sealed interface SelectionPointerEvent {
   public long identity();

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
         if (identity <= 0 || button < 0 || button > 2) {
            throw new IllegalArgumentException("A selection release requires a positive identity and a supported button");
         }
      }
   }

   public record Cancel(long identity) implements SelectionPointerEvent {
      public Cancel {
         if (identity <= 0) throw new IllegalArgumentException("A selection cancel requires a positive identity");
      }
   }
}
