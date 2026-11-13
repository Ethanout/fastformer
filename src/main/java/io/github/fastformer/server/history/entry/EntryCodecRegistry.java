package io.github.fastformer.server.history.entry;

import io.github.fastformer.common.persistence.IncompatibleWorldDataException;
import io.github.fastformer.core.registry.Registry;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Frozen, typed entry codecs. Missing or unknown types never fall back to a block entry. */
public final class EntryCodecRegistry {
   private final Registry<ResourceLocation, Registration<?>> codecs = Registry.create("history entry codecs");

   private EntryCodecRegistry() {}

   /** Assembly finishes before this registry is returned or shared with storage workers. */
   public static EntryCodecRegistry create(Consumer<EntryCodecRegistry> configure) {
      var registry = new EntryCodecRegistry();
      configure.accept(registry);
      registry.codecs.freeze();
      return registry;
   }

   public static EntryCodecRegistry standard() {
      return standard(registry -> {});
   }

   public static EntryCodecRegistry standard(Consumer<EntryCodecRegistry> configure) {
      return create(registry -> {
         registry.register(WorldChangeBatch.TYPE, WorldChangeBatch.class, new EntryCodec<>() {
            @Override public CompoundTag encode(WorldChangeBatch entry) throws IOException {
               return BlockChangeEntryCodec.encode(entry);
            }
            @Override public WorldChangeBatch decode(HolderLookup.Provider registries, CompoundTag root) throws IOException {
               return BlockChangeEntryCodec.decode(registries, root);
            }
         });
         configure.accept(registry);
      });
   }

   /** Duplicate IDs and registrations after assembly are rejected. */
   public <E extends HistoryEntry> void register(ResourceLocation id, Class<E> type, EntryCodec<E> codec) {
      codecs.register(id, new Registration<>(Objects.requireNonNull(type), Objects.requireNonNull(codec)));
   }

   public CompoundTag encode(HistoryEntry entry) throws IOException {
      if (entry == null || entry.type() == null) throw new IOException("Missing history entry type");
      CompoundTag root = lookup(entry.type()).encode(entry);
      root.putString("Type", entry.type().toString());
      return root;
   }

   public HistoryEntry decode(HolderLookup.Provider registries, CompoundTag root) throws IOException {
      ResourceLocation id = typeId(root);
      HistoryEntry entry = lookup(id).decode(registries, root);
      if (!id.equals(entry.type())) throw new IOException("History codec returned a different entry type: " + id);
      return entry;
   }

   static ResourceLocation typeId(CompoundTag root) throws IOException {
      if (root == null || !root.contains("Type", Tag.TAG_STRING)) {
         throw new IncompatibleWorldDataException("Missing history entry Type");
      }
      ResourceLocation id = ResourceLocation.tryParse(root.getString("Type"));
      if (id == null) throw new IOException("Invalid history entry Type");
      return id;
   }

   private Registration<?> lookup(ResourceLocation id) throws IOException {
      return codecs.get(id).orElseThrow(() -> new IncompatibleWorldDataException("Unknown history entry Type: " + id));
   }

   private record Registration<E extends HistoryEntry>(Class<E> type, EntryCodec<E> codec) {
      CompoundTag encode(HistoryEntry entry) throws IOException {
         if (!type.isInstance(entry)) throw new IOException("History entry does not match its registered codec");
         CompoundTag root = codec.encode(type.cast(entry));
         if (root == null) throw new IOException("History codec returned no encoded entry");
         return root;
      }
      E decode(HolderLookup.Provider registries, CompoundTag root) throws IOException {
         E entry = codec.decode(registries, root);
         if (!type.isInstance(entry)) throw new IOException("History codec returned an invalid entry");
         return entry;
      }
   }
}
