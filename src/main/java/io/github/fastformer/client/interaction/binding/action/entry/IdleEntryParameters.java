package io.github.fastformer.client.interaction.binding.action.entry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Follow-up protection and semantic middle entry are independent of the physical key. */
public record IdleEntryParameters(int guardMillis, boolean middleEntry) {
   public static final Codec<IdleEntryParameters> CODEC = RecordCodecBuilder.create(instance -> instance.group(
      Codec.intRange(0, 60_000).optionalFieldOf("guard_ms", 0).forGetter(IdleEntryParameters::guardMillis),
      Codec.BOOL.optionalFieldOf("middle_entry", false).forGetter(IdleEntryParameters::middleEntry)
   ).apply(instance, IdleEntryParameters::new));
}
