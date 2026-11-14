package io.github.fastformer.client.input.mouse;

/** Owns one consumed physical press until release or cancellation. */
public final class PointerButtonCapture {
   private Button button;

   public void capture(int buttonId) { button = new Button(buttonId); }
   public void clear() { button = null; }
   public boolean active() { return button != null; }
   public boolean owns(int buttonId) { return button != null && button.id == buttonId; }

   /** Numeric adapter for legacy pure routing records; absence is never a left click. */
   public int buttonId() { return button == null ? -1 : button.id; }

   private record Button(int id) {
      private Button {
         if (id < 0) throw new IllegalArgumentException("Physical pointer button must be non-negative: " + id);
      }
   }
}
