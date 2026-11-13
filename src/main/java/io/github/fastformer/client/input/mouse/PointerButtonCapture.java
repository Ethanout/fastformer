package io.github.fastformer.client.input.mouse;

/** Owns one consumed physical press until release or cancellation. */
public final class PointerButtonCapture {
   private Button button;

   public void capture(int buttonId) { button = Button.fromId(buttonId); }
   public void clear() { button = null; }
   public boolean active() { return button != null; }
   public boolean owns(int buttonId) { return button != null && button.id == buttonId; }

   /** Numeric adapter for legacy pure routing records; absence is never a left click. */
   public int buttonId() { return button == null ? -1 : button.id; }

   private enum Button {
      LEFT(0), RIGHT(1), MIDDLE(2);
      private final int id;
      Button(int id) { this.id = id; }
      static Button fromId(int id) {
         return switch (id) {
            case 0 -> LEFT;
            case 1 -> RIGHT;
            case 2 -> MIDDLE;
            default -> throw new IllegalArgumentException("Unsupported pointer button: " + id);
         };
      }
   }
}
