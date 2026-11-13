package io.github.fastformer.server.history.entry;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class EntryCodecRegistryTest {
   private static final ResourceLocation MARKER = ResourceLocation.fromNamespaceAndPath("test", "marker");
   private static final ResourceLocation OTHER = ResourceLocation.fromNamespaceAndPath("test", "other");
   private static final EntryCodec<MarkerEntry> CODEC = new EntryCodec<>() {
      @Override public CompoundTag encode(MarkerEntry entry) {
         var root = new CompoundTag();
         root.putString("Marker", entry.marker());
         return root;
      }
      @Override public MarkerEntry decode(HolderLookup.Provider registries, CompoundTag root) {
         return new MarkerEntry(MARKER, root.getString("Marker"));
      }
   };

   @Test
   void customEntryRoundTripsThroughItsRegisteredCodec() throws Exception {
      var registry = registry();
      var entry = new MarkerEntry(MARKER, "snapshot");
      var encoded = registry.encode(entry);
      assertEquals(MARKER.toString(), encoded.getString("Type"));
      assertEquals(entry, registry.decode(null, encoded));
   }

   @Test
   void missingUnknownMalformedAndWronglyTypedDiscriminatorsAreRejected() {
      var registry = registry();
      var root = new CompoundTag();
      assertThrows(IOException.class, () -> registry.decode(null, root));
      root.putInt("Type", 1);
      assertThrows(IOException.class, () -> registry.decode(null, root));
      root.putString("Type", "invalid id");
      assertThrows(IOException.class, () -> registry.decode(null, root));
      root.putString("Type", OTHER.toString());
      assertThrows(IOException.class, () -> registry.decode(null, root));
      assertThrows(IOException.class, () -> registry.decode(null, null));
   }

   @Test
   void duplicateRegistrationAndChangesAfterAssemblyAreRejected() {
      assertThrows(IllegalArgumentException.class, () -> EntryCodecRegistry.create(registry -> {
         registry.register(MARKER, MarkerEntry.class, CODEC);
         registry.register(MARKER, MarkerEntry.class, CODEC);
      }));
      assertThrows(IllegalStateException.class, () -> registry().register(OTHER, MarkerEntry.class, CODEC));
   }

   @Test
   void unregisteredAndMismatchedRuntimeEntriesAreRejected() {
      assertThrows(IOException.class, () -> registry().encode(new MarkerEntry(OTHER, "unknown")));
      var registry = EntryCodecRegistry.create(target -> target.register(MARKER, WorldChangeBatch.class, new EntryCodec<>() {
         @Override public CompoundTag encode(WorldChangeBatch entry) { throw new AssertionError("Wrong codec called"); }
         @Override public WorldChangeBatch decode(HolderLookup.Provider registries, CompoundTag root) { throw new AssertionError(); }
      }));
      assertThrows(IOException.class, () -> registry.encode(new MarkerEntry(MARKER, "wrong runtime type")));
   }

   @Test
   void codecCannotReturnAnEntryWithAnotherTypeOrNoEntry() {
      var registry = EntryCodecRegistry.create(target -> target.register(OTHER, MarkerEntry.class, CODEC));
      var root = new CompoundTag();
      root.putString("Type", OTHER.toString());
      assertThrows(IOException.class, () -> registry.decode(null, root));
      var empty = EntryCodecRegistry.create(target -> target.register(MARKER, MarkerEntry.class, new EntryCodec<>() {
         @Override public CompoundTag encode(MarkerEntry entry) { return null; }
         @Override public MarkerEntry decode(HolderLookup.Provider registries, CompoundTag data) { return null; }
      }));
      root.putString("Type", MARKER.toString());
      assertThrows(IOException.class, () -> empty.decode(null, root));
      assertThrows(IOException.class, () -> empty.encode(new MarkerEntry(MARKER, "empty")));
   }

   private static EntryCodecRegistry registry() {
      return EntryCodecRegistry.create(target -> target.register(MARKER, MarkerEntry.class, CODEC));
   }

   private record MarkerEntry(ResourceLocation type, String marker) implements HistoryEntry {
      @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      @Override public UUID operationId() { return null; }
      @Override public HistoryEntry withOperationId(UUID id) { return this; }
      @Override public int size() { return 1; }
      @Override public int estimatedBytes() { return 32; }
      @Override public HistoryBounds bounds() { return new HistoryBounds(0, 0, 0, 0, 0, 0); }
      @Override public BlockPos position(int index) { return BlockPos.ZERO; }
      @Override public int match(ServerLevel level, int index, boolean undo) { throw new UnsupportedOperationException(); }
      @Override public boolean apply(ServerLevel level, int index, boolean undo, int flags) { throw new UnsupportedOperationException(); }
   }
}
