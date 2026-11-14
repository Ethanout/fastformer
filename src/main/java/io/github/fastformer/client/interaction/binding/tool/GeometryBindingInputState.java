package io.github.fastformer.client.interaction.binding.tool;

import java.util.HashMap;
import java.util.Map;

/** Keeps the vanilla callback and queued commands bound to the physical press's policy revision. */
public final class GeometryBindingInputState {
   private final Map<Integer, GeometryBindingInput.Result> presses = new HashMap<>();
   private long generation;
   public void pressed(int button, GeometryBindingInput.Result result) { presses.put(button, result); }
   public GeometryBindingInput.Result interaction(int button) { return presses.get(button); }
   public long generation() { return generation; }
   public boolean accepts(long captured) { return generation == captured; }
   public void clear() { presses.clear(); generation++; }
}
