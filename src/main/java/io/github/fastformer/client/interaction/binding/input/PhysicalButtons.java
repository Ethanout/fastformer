package io.github.fastformer.client.interaction.binding.input;

import java.util.HashSet;
import java.util.Set;

/** Distinguishes a new physical press from repeats until the matching release or scope reset. */
public final class PhysicalButtons {
   private final Set<Integer> down = new HashSet<>();
   public boolean press(int button) { return down.add(button); }
   public void release(int button) { down.remove(button); }
   public void clear() { down.clear(); }
}
