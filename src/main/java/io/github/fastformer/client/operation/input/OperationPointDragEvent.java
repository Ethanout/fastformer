package io.github.fastformer.client.operation.input;

/** Pairs a queued control-point press with its physical release. */
public sealed interface OperationPointDragEvent {
   public long identity();

   public record Press(OperationPointDragPress snapshot) implements OperationPointDragEvent {
      @Override public long identity() { return snapshot.identity(); }
   }
   public record Release(long identity, int button, long occurredAtNanos, net.minecraft.world.phys.Vec3 eye,
      net.minecraft.world.phys.Vec3 view) implements OperationPointDragEvent { }
   public record Cancel(long identity) implements OperationPointDragEvent { }
}
