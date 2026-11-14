package io.github.fastformer.client.interaction.binding.tool;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Keeps physical ownership and resource revisions separate from logical tool buttons. */
public final class SelectionBindingInputState {
   private final Map<Integer, SelectionBindingInput.Result> presses = new HashMap<>();
   private final Set<Integer> captures = new HashSet<>();
   private long generation;
   public void pressed(int button, SelectionBindingInput.Result policy) { presses.put(button, policy); }
   public SelectionBindingInput.Result interaction(int button) { return presses.get(button); }
   public void capture(int button) { if (button >= 0) captures.add(button); }
   public boolean owns(int button) { return captures.contains(button); }
   public boolean active() { return !captures.isEmpty(); }
   public boolean release(int button) { return captures.remove(button); }
   public long generation() { return generation; }
   public boolean accepts(long captured) { return generation == captured; }
   public void clear() { presses.clear(); captures.clear(); generation++; }
}
