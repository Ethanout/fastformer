package io.github.fastformer.client.operation.input;

/** Keeps delayed operation-point callbacks paired without reading later pointer state. */
public final class OperationPointDragCapture {
   public record Dispatch(long identity, long clickToken, long pointerToken) { }
   private long sequence;
   private long captured;
   private int button = -1;
   private Dispatch dispatched;

   public OperationPointDragEvent.Cancel superseded() {
      return captured == 0L ? null : new OperationPointDragEvent.Cancel(captured);
   }

   public OperationPointDragPress capture(OperationPointDragPress press) {
      captured = ++sequence;
      button = press.button();
      return new OperationPointDragPress(captured, press.button(), press.occurredAtNanos(), press.selection(), press.revision(),
         press.callbackScope(), press.pointIndex(), press.initialPoint(), press.plane(), press.planeGrabOffset(), press.line(),
         press.lineGrabBaseline(), press.axisBaselines(), press.constraint(), press.eye(), press.view());
   }

   public OperationPointDragEvent.Release release(int releasedButton, long occurredAtNanos,
      net.minecraft.world.phys.Vec3 eye, net.minecraft.world.phys.Vec3 view) {
      if (captured == 0L || button != releasedButton) return null;
      long identity = captured;
      captured = 0L;
      button = -1;
      return new OperationPointDragEvent.Release(identity, releasedButton, occurredAtNanos, eye, view);
   }

   public void dispatched(long identity, long clickToken, long pointerToken) {
      dispatched = new Dispatch(identity, clickToken, pointerToken);
   }

   public Dispatch take(long identity) {
      if (dispatched == null || dispatched.identity() != identity) return null;
      Dispatch result = dispatched;
      dispatched = null;
      return result;
   }

   public void clear() { captured = 0L; button = -1; dispatched = null; }
}
