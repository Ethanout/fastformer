package io.github.fastformer.common.interaction.binding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Immutable server-owned constraints; absence of a field preserves normal session behavior. */
public record SessionProfile(ResourceLocation session, Optional<String> maxStage,
   Map<String, ResourceLocation> lockedModes, boolean allowModeSwitch, String middleConfirm, Entry entry) {
   private static final Set<String> STAGES = Set.of("point", "line", "face", "volume");
   private static final Set<String> REQUIREMENTS = Set.of("creative", "holding_placeable", "aiming_block", "within_reach");
   public static final SessionProfile NONE = new SessionProfile(id("quick_shape"), Optional.empty(),
      Map.of(), true, "settings", Entry.DEFAULT);
   public static final Codec<SessionProfile> CODEC = RecordCodecBuilder.<SessionProfile>create(instance -> instance.group(
      ResourceLocation.CODEC.fieldOf("session").forGetter(SessionProfile::session),
      Codec.STRING.optionalFieldOf("max_stage").forGetter(SessionProfile::maxStage),
      Codec.unboundedMap(Codec.STRING, ResourceLocation.CODEC).optionalFieldOf("locked_modes", Map.of()).forGetter(SessionProfile::lockedModes),
      Codec.BOOL.optionalFieldOf("allow_mode_switch", true).forGetter(SessionProfile::allowModeSwitch),
      Codec.STRING.optionalFieldOf("middle_confirm", "settings").forGetter(SessionProfile::middleConfirm),
      Entry.CODEC.optionalFieldOf("entry", Entry.DEFAULT).forGetter(SessionProfile::entry)
   ).apply(instance, SessionProfile::new)).validate(SessionProfile::validate);

   public SessionProfile {
      java.util.Objects.requireNonNull(session, "session");
      maxStage = java.util.Objects.requireNonNull(maxStage, "maxStage");
      lockedModes = Map.copyOf(lockedModes);
      java.util.Objects.requireNonNull(middleConfirm, "middleConfirm");
      java.util.Objects.requireNonNull(entry, "entry");
   }

   private static DataResult<SessionProfile> validate(SessionProfile profile) {
      if (profile.maxStage.filter(stage -> !STAGES.contains(stage)).isPresent()
         || !STAGES.containsAll(profile.lockedModes.keySet())) {
         return DataResult.error(() -> "Unknown session stage");
      }
      if (!Set.of("settings", "always").contains(profile.middleConfirm))
         return DataResult.error(() -> "Unknown middle confirmation policy");
      if (!Set.of("surface", "embedded").contains(profile.entry.anchor)
         || !REQUIREMENTS.containsAll(profile.entry.requires))
         return DataResult.error(() -> "Unknown session entry constraint");
      for (var locked : profile.lockedModes.entrySet()) {
         if (!locked.getKey().equals("line") || !Set.of("fastformer:axis", "fastformer:free_scroll", "fastformer:raycast")
            .contains(locked.getValue().toString())) return DataResult.error(() -> "Unsupported locked session mode");
      }
      return DataResult.success(profile);
   }

   private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fastformer", path); }

   public record Entry(String anchor, List<String> requires) {
      public static final Entry DEFAULT = new Entry("surface", List.of());
      public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
         Codec.STRING.optionalFieldOf("anchor", "surface").forGetter(Entry::anchor),
         Codec.STRING.listOf().optionalFieldOf("requires", List.of()).forGetter(Entry::requires)
      ).apply(instance, Entry::new));
      public Entry { requires = List.copyOf(requires); }
   }
}
