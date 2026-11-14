package io.github.fastformer.fastplace.geometry.generation.face;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Failure is explicit and can never be confused with a generated set of synthetic blocks. */
public sealed interface FaceRasterResult {
    record Filled(Set<BlockPos> blocks) implements FaceRasterResult {
        public Filled {
            LinkedHashSet<BlockPos> copy = new LinkedHashSet<>();
            blocks.forEach(position -> copy.add(position.immutable()));
            blocks = Collections.unmodifiableSet(copy);
        }
    }
    record LimitExceeded() implements FaceRasterResult {}
    record NotApplicable() implements FaceRasterResult {}
    record NoValidCandidate() implements FaceRasterResult {}
}
