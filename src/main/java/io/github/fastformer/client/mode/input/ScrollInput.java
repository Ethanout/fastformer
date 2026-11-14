package io.github.fastformer.client.mode.input;

/** One physical wheel event, before any workflow changes its state. */
public record ScrollInput(double delta) {
   public int direction() { return delta > 0.0 ? 1 : delta < 0.0 ? -1 : 0; }
}
