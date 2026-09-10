package io.github.fastformer.fastplace.geometry.generation;

import java.util.Iterator;
import net.minecraft.core.BlockPos;

/** Allows a task-owned generated set to release packed positions as they are consumed. */
public interface DrainingBlockSet {
   Iterator<BlockPos> drainingIterator();
}
