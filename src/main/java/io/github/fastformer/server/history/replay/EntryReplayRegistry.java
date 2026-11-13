package io.github.fastformer.server.history.replay;

import io.github.fastformer.core.registry.Registry;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Frozen, typed replay factories. Opening a state never narrows an unrelated entry to a block batch. */
public final class EntryReplayRegistry {
   private final Registry<ResourceLocation, Registration<?>> factories = Registry.create("history replay factories");

   private EntryReplayRegistry() {}

   public static EntryReplayRegistry create(Consumer<EntryReplayRegistry> configure) {
      EntryReplayRegistry registry = new EntryReplayRegistry();
      configure.accept(registry);
      registry.factories.freeze();
      return registry;
   }

   public static EntryReplayRegistry standard() {
      return standard(registry -> {});
   }

   public static EntryReplayRegistry standard(Consumer<EntryReplayRegistry> configure) {
      return create(registry -> {
         registry.register(WorldChangeBatch.TYPE, WorldChangeBatch.class, BlockEntryReplay::new);
         configure.accept(registry);
      });
   }

   public <E extends HistoryEntry> void register(ResourceLocation id, Class<E> type,
      BiFunction<E, Boolean, EntryReplayState> factory) {
      factories.register(id, new Registration<>(Objects.requireNonNull(type), Objects.requireNonNull(factory)));
   }

   public EntryReplayState open(HistoryEntry entry, boolean undo) {
      return registration(entry).open(entry, undo);
   }

   public void validate(HistoryEntry entry) {
      registration(entry).requireType(entry);
   }

   private Registration<?> registration(HistoryEntry entry) {
      Objects.requireNonNull(entry, "entry");
      return factories.get(entry.type()).orElseThrow(
         () -> new IllegalArgumentException("Unknown history replay type: " + entry.type()));
   }

   private record Registration<E extends HistoryEntry>(Class<E> type, BiFunction<E, Boolean, EntryReplayState> factory) {
      EntryReplayState open(HistoryEntry entry, boolean undo) {
         requireType(entry);
         return Objects.requireNonNull(factory.apply(type.cast(entry), undo), "History replay factory returned no state");
      }
      void requireType(HistoryEntry entry) {
         if (!type.isInstance(entry)) throw new IllegalArgumentException("History entry does not match its replay factory");
      }
   }
}
