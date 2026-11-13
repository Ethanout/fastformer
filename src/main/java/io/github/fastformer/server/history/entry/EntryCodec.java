package io.github.fastformer.server.history.entry;

import java.io.IOException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Codec for one history entry type; the registry owns and validates the Type discriminator. */
public interface EntryCodec<E extends HistoryEntry> {
   CompoundTag encode(E entry) throws IOException;
   E decode(HolderLookup.Provider registries, CompoundTag root) throws IOException;
}
