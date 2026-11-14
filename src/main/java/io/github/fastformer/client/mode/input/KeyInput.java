package io.github.fastformer.client.mode.input;

/** Physical key facts, independent of Minecraft key mappings or workflow state. */
public record KeyInput(int key, int action, boolean controlDown) {
   public boolean press() { return action == 1; }
   public boolean enter() { return key == 257 || key == 335; }
}
