package io.github.fastformer.client.interaction.binding.option;

import com.google.gson.*;
import io.github.fastformer.client.session.ClientStorageQueue;
import io.github.fastformer.core.persist.AtomicFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

/** Client-owned option values, coalesced and saved atomically on the bounded storage queue. */
public final class OptionStore {
   private final Path file;
   private final ClientStorageQueue storage;
   private final Map<ResourceLocation, Boolean> values = new HashMap<>();
   private Map<ResourceLocation, BindingOption> definitions = Map.of();
   private final Set<ResourceLocation> editedBeforeLoad = new HashSet<>();
   private boolean loading;
   private boolean loaded;
   private boolean saving;
   private boolean dirty;
   private long revision;
   private long retryAt;

   public OptionStore(Path file, ClientStorageQueue storage) { this.file = file; this.storage = storage; }

   public void install(List<BindingOption> options) {
      Map<ResourceLocation, BindingOption> next = new LinkedHashMap<>();
      options.forEach(option -> next.put(option.id(), option));
      definitions = Collections.unmodifiableMap(next);
   }

   public List<BindingOption> definitions() { return List.copyOf(definitions.values()); }
   public boolean enabled(ResourceLocation id) {
      BindingOption definition = definitions.get(id);
      return definition != null && values.getOrDefault(id, definition.defaultValue());
   }

   public void set(ResourceLocation id, boolean enabled) {
      if (!definitions.containsKey(id)) throw new IllegalArgumentException("Unknown binding option: " + id);
      values.put(id, enabled);
      if (!loaded) editedBeforeLoad.add(id);
      revision++;
      dirty = true;
   }

   public void tick(long now) {
      if (!loaded && !loading && now >= retryAt) {
         loading = storage.submit(this::read, (stored, failure) -> {
            loading = false;
            if (failure != null) { warn(failure); retryAt = System.nanoTime() + 1_000_000_000L; return; }
            stored.forEach((id, value) -> { if (!editedBeforeLoad.contains(id)) values.put(id, value); });
            editedBeforeLoad.clear();
            loaded = true;
         });
      }
      if (!loaded || !dirty || saving || now < retryAt) return;
      Map<ResourceLocation, Boolean> snapshot = Map.copyOf(values);
      long savedRevision = revision;
      saving = storage.submit(() -> {
         JsonObject object = new JsonObject();
         snapshot.forEach((id, enabled) -> object.addProperty(id.toString(), enabled));
         AtomicFiles.write(file, output -> output.write(new GsonBuilder().setPrettyPrinting().create().toJson(object).getBytes(StandardCharsets.UTF_8)));
         return true;
      }, (ignored, failure) -> {
         saving = false;
         if (failure != null) { retryAt = System.nanoTime() + 1_000_000_000L; warn(failure); }
         else if (revision == savedRevision) dirty = false;
      });
   }

   private Map<ResourceLocation, Boolean> read() throws java.io.IOException {
      if (!Files.exists(file)) return Map.of();
      try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
         JsonObject source = JsonParser.parseReader(reader).getAsJsonObject();
         Map<ResourceLocation, Boolean> result = new HashMap<>();
         for (var entry : source.entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isBoolean())
               throw new JsonParseException("Option value must be boolean");
            result.put(ResourceLocation.parse(entry.getKey()), entry.getValue().getAsBoolean());
         }
         return result;
      } catch (RuntimeException malformed) {
         AtomicFiles.move(file, file.resolveSibling(file.getFileName() + ".bak-" + System.currentTimeMillis()));
         warn(malformed);
         return Map.of();
      }
   }

   private void warn(Throwable failure) {
      com.mojang.logging.LogUtils.getLogger().warn("Unable to read or save interaction options {}", file, failure);
   }
}
