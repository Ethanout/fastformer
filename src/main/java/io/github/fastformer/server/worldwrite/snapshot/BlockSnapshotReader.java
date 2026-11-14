package io.github.fastformer.server.worldwrite.snapshot;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** World reads used during incremental snapshot admission, before a writer changes any cells. */
@FunctionalInterface
public interface BlockSnapshotReader {
   Optional<ReversibleBlockSnapshot> capture(ServerLevel level, BlockPos position);
   default boolean isAir(ServerLevel level, BlockPos position) { return level.getBlockState(position).isAir(); }
   static BlockSnapshotReader live() { return ReversibleBlockSnapshot::capture; }
}
