package io.github.fastformer.server.task.block;

import io.github.fastformer.common.world.memory.MemoryAdmission;

/** Applies an owner's budget limit to an estimate derived from the task's actual retained data. */
@FunctionalInterface
public interface BlockTaskMemoryPolicy {
   MemoryAdmission admit(MemoryAdmission estimate);
   static BlockTaskMemoryPolicy standard() { return estimate -> estimate; }
}
