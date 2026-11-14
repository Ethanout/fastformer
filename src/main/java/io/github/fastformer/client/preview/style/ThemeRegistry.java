package io.github.fastformer.client.preview.style;

import io.github.fastformer.core.registry.Registry;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Publishes selection, values and revision together. Reloads retain the configured selection intent. */
public final class ThemeRegistry {
   private volatile Snapshot snapshot = assemble(0, BuiltinThemes.CLASSIC, Map.of());

   public Snapshot snapshot() { return snapshot; }

   public synchronized void select(ResourceLocation requested) {
      Objects.requireNonNull(requested, "requested");
      Snapshot previous = snapshot;
      if (previous.requested().equals(requested)) return;
      snapshot = new Snapshot(previous.revision() + 1, requested, previous.registry, previous.definitions());
   }

   /** The whole prepared batch replaces the previous pack definitions in one publication. */
   public synchronized void install(Map<ResourceLocation, ThemeDefinition> loaded) {
      snapshot = assemble(snapshot.revision() + 1, snapshot.requested(), loaded);
   }

   public synchronized void invalidateGeometry() {
      Snapshot previous = snapshot;
      snapshot = new Snapshot(previous.revision() + 1, previous.requested(), previous.registry, previous.definitions());
   }

   private static Snapshot assemble(long revision, ResourceLocation requested, Map<ResourceLocation, ThemeDefinition> loaded) {
      Map<ResourceLocation, ThemeDefinition> entries = new LinkedHashMap<>();
      entries.put(BuiltinThemes.CLASSIC, BuiltinThemes.definition(BuiltinThemes.CLASSIC));
      entries.put(BuiltinThemes.HUMANIST, BuiltinThemes.definition(BuiltinThemes.HUMANIST));
      loaded.forEach((id, definition) -> {
         if (!id.equals(definition.id())) throw new IllegalArgumentException("Theme key differs from its definition: " + id);
         entries.put(id, definition);
      });
      Registry<ResourceLocation, ThemeDefinition> registry = Registry.create("visual themes");
      entries.forEach(registry::register);
      registry.freeze();
      List<ThemeDefinition> definitions = registry.ordered(Comparator.comparingInt(ThemeRegistry::order)
         .thenComparing(theme -> theme.id().toString())).toList();
      return new Snapshot(revision, requested, registry, definitions);
   }

   private static int order(ThemeDefinition definition) {
      return definition.id().equals(BuiltinThemes.CLASSIC) ? 0 : definition.id().equals(BuiltinThemes.HUMANIST) ? 1 : 2;
   }

   public static final class Snapshot {
      private final long revision;
      private final ResourceLocation requested;
      private final Registry<ResourceLocation, ThemeDefinition> registry;
      private final List<ThemeDefinition> definitions;
      private final ThemeDefinition selected;

      private Snapshot(long revision, ResourceLocation requested, Registry<ResourceLocation, ThemeDefinition> registry,
         List<ThemeDefinition> definitions) {
         this.revision = revision;
         this.requested = requested;
         this.registry = registry;
         this.definitions = List.copyOf(definitions);
         this.selected = registry.get(requested).orElseGet(() -> registry.get(BuiltinThemes.CLASSIC).orElseThrow());
      }

      public long revision() { return revision; }
      public ResourceLocation requested() { return requested; }
      public ThemeDefinition selected() { return selected; }
      public List<ThemeDefinition> definitions() { return definitions; }

      public ResourceLocation next() {
         return definitions.get((definitions.indexOf(selected) + 1) % definitions.size()).id();
      }
   }
}
