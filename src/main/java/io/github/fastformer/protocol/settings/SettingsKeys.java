package io.github.fastformer.protocol.settings;

import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** Shared setting declarations; server handlers and UI controls refer to these same typed keys. */
public final class SettingsKeys {
   public static final SettingCatalog CATALOG = new SettingCatalog();
   public static final SettingKey<Boolean> MIDDLE_CONFIRM = flag("middle_confirm");
   public static final SettingKey<Boolean> EMPTY_HAND_WRENCH = flag("empty_hand_wrench");
   public static final SettingKey<Boolean> VANILLA_BLOCK_HISTORY = flag("vanilla_block_history");
   public static final SettingKey<Boolean> SKIP_REPEATED_CROP_STAGES = flag("skip_repeated_crop_stages");
   public static final SettingKey<Boolean> FALLING_DISABLED = flag("falling_disabled");
   public static final SettingKey<Integer> WORLD_HISTORY_LIMIT = number("world_history_limit");
   public static final SettingKey<Integer> SESSION_HISTORY_LIMIT = number("session_history_limit");
   public static final SettingKey<Integer> RECENT_AFTER = number("compression/recent_after");
   public static final SettingKey<Integer> RECENT_GROUP = number("compression/recent_group");
   public static final SettingKey<Integer> OLDER_AFTER = number("compression/older_after");
   public static final SettingKey<Integer> OLDER_GROUP = number("compression/older_group");
   public static final SettingKey<Integer> OLDEST_AFTER = number("compression/oldest_after");
   public static final SettingKey<Integer> OLDEST_GROUP = number("compression/oldest_group");
   public static final SettingKey<OperationConflictMode> PLACEMENT_CONFLICT = enumeration("placement_conflict", OperationConflictMode.class);
   public static final SettingKey<PlacementUpdateMode> PLACEMENT_UPDATE = enumeration("placement_update", PlacementUpdateMode.class);
   public static final SettingKey<PlacementEffectValue> PLACEMENT_EFFECT = CATALOG.register(id("placement_effect"),
      PlacementEffectValue.class, PlacementEffectValue.CODEC);

   static { CATALOG.freeze(); }

   private SettingsKeys() {}
   private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fastformer", path); }
   private static SettingKey<Boolean> flag(String path) { return CATALOG.register(id(path), Boolean.class, ByteBufCodecs.BOOL); }
   private static SettingKey<Integer> number(String path) { return CATALOG.register(id(path), Integer.class, ByteBufCodecs.VAR_INT); }
   private static <E extends Enum<E>> SettingKey<E> enumeration(String path, Class<E> type) {
      StreamCodec<FriendlyByteBuf, E> codec = StreamCodec.of((buffer, value) -> buffer.writeEnum(value), buffer -> buffer.readEnum(type));
      return CATALOG.register(id(path), type, codec);
   }
}
