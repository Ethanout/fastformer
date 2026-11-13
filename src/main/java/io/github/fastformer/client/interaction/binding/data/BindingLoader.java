package io.github.fastformer.client.interaction.binding.data;

import com.google.gson.*;
import io.github.fastformer.client.interaction.binding.BindingConfiguration;
import io.github.fastformer.core.persist.AtomicFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.profiling.ProfilerFiller;

/** Resource reload builds one complete candidate snapshot, then publishes it on the client thread. */
public final class BindingLoader extends SimplePreparableReloadListener<BindingFile> {
   private final Path userFile;
   public BindingLoader(Path userFile) { this.userFile = userFile; }

   @Override protected BindingFile prepare(ResourceManager resources, ProfilerFiller profiler) {
      Map<net.minecraft.resources.ResourceLocation, InteractionBinding> bindings = new LinkedHashMap<>();
      Map<net.minecraft.resources.ResourceLocation, io.github.fastformer.client.interaction.binding.option.BindingOption> options = new LinkedHashMap<>();
      List<String> diagnostics = new ArrayList<>();
      for (var entry : resources.listResources("fastformer/bindings", id -> id.getPath().endsWith(".json")).entrySet().stream()
         .sorted(Map.Entry.comparingByKey()).toList()) {
         try (var reader = entry.getValue().openAsReader()) {
            BindingFile file = BindingFile.parse(JsonParser.parseReader(reader));
            for (InteractionBinding binding : file.bindings()) {
               var id = binding.id();
               if (id.getPath().startsWith("generated/")) id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                  entry.getKey().getNamespace(), "generated/" + entry.getKey().getPath() + "/" + id.getPath().substring(10));
               var scoped = new InteractionBinding(id, binding.context(), binding.input(), binding.action(), binding.priority(), binding.enabled(), binding.option());
               if (bindings.putIfAbsent(id, scoped) != null) diagnostics.add("Duplicate binding: " + id);
            }
            file.options().forEach(option -> options.put(option.id(), option));
            file.diagnostics().forEach(error -> diagnostics.add(entry.getKey() + ": " + error));
         } catch (java.io.IOException | RuntimeException failure) { diagnostics.add(entry.getKey() + ": " + failure.getMessage()); }
      }
      BindingFile defaults = new BindingFile(List.copyOf(bindings.values()), List.copyOf(options.values()), diagnostics);
      if (!Files.exists(userFile)) return defaults;
      try (var reader = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
         return UserOverrides.merge(defaults, JsonParser.parseReader(reader).getAsJsonObject());
      } catch (java.io.IOException | RuntimeException failure) {
         try { AtomicFiles.move(userFile, userFile.resolveSibling(userFile.getFileName() + ".bak-" + System.currentTimeMillis())); }
         catch (java.io.IOException backupFailure) { failure.addSuppressed(backupFailure); }
         com.mojang.logging.LogUtils.getLogger().warn("Unable to load binding overrides {}; using resource defaults", userFile, failure);
         diagnostics.add("User bindings: " + failure.getMessage());
         return new BindingFile(defaults.bindings(), defaults.options(), diagnostics);
      }
   }

   @Override protected void apply(BindingFile file, ResourceManager resources, ProfilerFiller profiler) {
      BindingConfiguration.instance().install(file);
   }
}
