package io.github.fastformer.client.interaction.binding.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import io.github.fastformer.client.interaction.binding.input.InputPattern;
import io.github.fastformer.client.interaction.binding.option.BindingOption;
import io.github.fastformer.common.interaction.binding.ActionSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Reads flat rules and inherited context trees; one invalid rule does not discard its siblings. */
public record BindingFile(List<InteractionBinding> bindings, List<BindingOption> options, List<String> diagnostics) {
   private static final Set<String> NODE_TYPES = Set.of("session", "stage", "mode", "profile");
   private static final int MAX_DEPTH = 32;
   private static final int MAX_BINDINGS = 4096;
   public static final Codec<BindingFile> CODEC = Codec.PASSTHROUGH.comapFlatMap(dynamic -> {
      try { return DataResult.success(parse(dynamic.convert(JsonOps.INSTANCE).getValue())); }
      catch (RuntimeException failure) { return DataResult.error(() -> "Invalid binding file: " + failure.getMessage()); }
   }, file -> new Dynamic<>(JsonOps.INSTANCE, file.encode()));

   public BindingFile {
      bindings = List.copyOf(bindings);
      options = List.copyOf(options);
      diagnostics = List.copyOf(diagnostics);
   }

   public static BindingFile parse(JsonElement source) {
      if (source == null || !source.isJsonObject()) throw new IllegalArgumentException("Binding file must be an object");
      JsonObject root = source.getAsJsonObject();
      List<InteractionBinding> bindings = new ArrayList<>();
      List<BindingOption> options = new ArrayList<>();
      List<String> errors = new ArrayList<>();
      Optional<ResourceLocation> option = root.has("option") ? Optional.of(id(root.get("option").getAsString())) : Optional.empty();
      option.ifPresent(value -> options.add(new BindingOption(value, root.has("option_default") && root.get("option_default").getAsBoolean())));
      if (root.has("options")) {
         for (JsonElement entry : root.getAsJsonArray("options")) {
            JsonObject definition = entry.getAsJsonObject();
            BindingOption definitionValue = new BindingOption(id(definition.get("id").getAsString()),
               definition.has("default") && definition.get("default").getAsBoolean());
            if (options.stream().anyMatch(existing -> existing.id().equals(definitionValue.id()))) {
               throw new IllegalArgumentException("Duplicate option: " + definitionValue.id());
            }
            options.add(definitionValue);
         }
      }
      if (root.has("bindings")) {
         for (JsonElement entry : root.getAsJsonArray("bindings")) {
            try { add(bindings, flat(entry.getAsJsonObject(), option)); }
            catch (RuntimeException failure) { errors.add("binding[" + bindings.size() + "]: " + failure.getMessage()); }
         }
      }
      if (root.has("tree")) tree(root.getAsJsonObject("tree"), Map.of(), option, "tree", 0, bindings, errors);
      return new BindingFile(bindings, options, errors);
   }

   private static InteractionBinding flat(JsonObject object, Optional<ResourceLocation> inheritedOption) {
      Map<String, JsonElement> context = object.has("context") ? map(object.getAsJsonObject("context")) : Map.of();
      InputPattern input = InputPattern.CODEC.parse(JsonOps.INSTANCE, object.get("input")).getOrThrow();
      return new InteractionBinding(id(object.get("id").getAsString()), context, input, ActionSpec.parse(object.get("action")),
         object.has("priority") ? object.get("priority").getAsInt() : 0,
         !object.has("enabled") || object.get("enabled").getAsBoolean(),
         object.has("option") ? Optional.of(id(object.get("option").getAsString())) : inheritedOption);
   }

   private static void tree(JsonObject node, Map<String, JsonElement> inherited, Optional<ResourceLocation> option,
      String path, int depth, List<InteractionBinding> bindings, List<String> errors) {
      if (depth > MAX_DEPTH) { errors.add(path + ": tree is too deep"); return; }
      for (var entry : node.entrySet()) {
         String key = entry.getKey();
         try {
            int separator = key.indexOf(':');
            if (separator > 0 && NODE_TYPES.contains(key.substring(0, separator))) {
               Map<String, JsonElement> context = new LinkedHashMap<>(inherited);
               context.put(key.substring(0, separator), new com.google.gson.JsonPrimitive(key.substring(separator + 1)));
               tree(entry.getValue().getAsJsonObject(), context, option, path + "/" + key, depth + 1, bindings, errors);
            } else {
               String[] trigger = key.split("/", -1);
               if (trigger.length != 2) throw new IllegalArgumentException("Expected node:value or button/gesture");
               JsonArray choices = entry.getValue().isJsonArray() ? entry.getValue().getAsJsonArray() : new JsonArray();
               if (!entry.getValue().isJsonArray()) choices.add(entry.getValue());
               int choice = 0;
               for (JsonElement leaf : choices) {
                  try {
                     JsonObject rule = leaf.getAsJsonObject().deepCopy();
                     Map<String, JsonElement> context = new LinkedHashMap<>(inherited);
                     if (rule.has("if")) context.putAll(map(rule.getAsJsonObject("if")));
                     JsonObject input = rule.has("input") ? rule.getAsJsonObject("input") : new JsonObject();
                     input.addProperty("button", trigger[0]);
                     input.addProperty("gesture", trigger[1]);
                     rule.add("input", input);
                     JsonObject encodedContext = new JsonObject();
                     context.forEach(encodedContext::add);
                     rule.add("context", encodedContext);
                     if (!rule.has("id")) rule.addProperty("id", "fastformer:generated/" + path.replace(':', '_') + "/" + key + "/" + choice);
                     add(bindings, flat(rule, option));
                  } catch (RuntimeException failure) { errors.add(path + "/" + key + "[" + choice + "]: " + failure.getMessage()); }
                  choice++;
               }
            }
         } catch (RuntimeException failure) { errors.add(path + "/" + key + ": " + failure.getMessage()); }
      }
   }

   private static void add(List<InteractionBinding> bindings, InteractionBinding binding) {
      if (bindings.size() >= MAX_BINDINGS) throw new IllegalArgumentException("Too many bindings");
      if (bindings.stream().anyMatch(existing -> existing.id().equals(binding.id()))) throw new IllegalArgumentException("Duplicate binding: " + binding.id());
      bindings.add(binding);
   }

   private static Map<String, JsonElement> map(JsonObject object) {
      Map<String, JsonElement> result = new LinkedHashMap<>();
      object.entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().deepCopy()));
      return result;
   }

   private static ResourceLocation id(String value) {
      ResourceLocation id = ResourceLocation.tryParse(value);
      if (id == null) throw new IllegalArgumentException("Invalid binding id: " + value);
      return id;
   }

   public JsonObject encode() {
      JsonObject root = new JsonObject();
      JsonArray definitions = new JsonArray();
      for (BindingOption option : options) {
         JsonObject definition = new JsonObject();
         definition.addProperty("id", option.id().toString());
         definition.addProperty("default", option.defaultValue());
         definitions.add(definition);
      }
      root.add("options", definitions);
      JsonArray encoded = new JsonArray();
      for (InteractionBinding binding : bindings) {
         JsonObject rule = new JsonObject();
         rule.addProperty("id", binding.id().toString());
         JsonObject context = new JsonObject();
         binding.context().forEach(context::add);
         rule.add("context", context);
         rule.add("input", InputPattern.CODEC.encodeStart(JsonOps.INSTANCE, binding.input()).getOrThrow());
         rule.add("action", binding.action().encode());
         rule.addProperty("priority", binding.priority());
         rule.addProperty("enabled", binding.enabled());
         binding.option().ifPresent(option -> rule.addProperty("option", option.toString()));
         encoded.add(rule);
      }
      root.add("bindings", encoded);
      return root;
   }
}
