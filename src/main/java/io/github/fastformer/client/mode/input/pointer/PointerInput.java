package io.github.fastformer.client.mode.input.pointer;

/** Physical button event; capture ownership and execution stay at the input boundary. */
public record PointerInput(int action, int button) {
   public boolean press(int expectedButton) { return action == 1 && button == expectedButton; }
}
