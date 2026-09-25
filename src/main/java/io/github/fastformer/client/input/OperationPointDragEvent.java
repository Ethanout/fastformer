package io.github.fastformer.client.input;

/** Pairs a queued control-point press with its physical release. */
sealed interface OperationPointDragEvent {
   long identity();

   record Press(OperationPointDragPress snapshot) implements OperationPointDragEvent {
      @Override public long identity() { return snapshot.identity(); }
   }
   record Release(long identity, int button, long occurredAtNanos, net.minecraft.world.phys.Vec3 eye,
      net.minecraft.world.phys.Vec3 view) implements OperationPointDragEvent { }
   record Cancel(long identity) implements OperationPointDragEvent { }
}
