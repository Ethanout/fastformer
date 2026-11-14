package io.github.fastformer.common.tinker;

/** Per-tick policy retained when a chunk is saved and loaded. */
public interface ProtectedInteractionTick {
   String NBT_KEY = "fastformer:suppress_neighbors";
   boolean fastformer$suppressesNeighbors();
   void fastformer$suppressNeighbors(boolean suppress);
}
