package io.github.fastformer.fastplace.history;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.event.config.ModConfigEvent;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;
import io.github.fastformer.fastplace.history.TinkerHistoryGroupingRules.KeyPart;

/** Per-world server settings for completed operation history. */
public final class HistoryStorageConfig {
   public static final ModConfigSpec SPEC;
   private static final ModConfigSpec.IntValue OWNER_DISK_MIB;
   private static final ModConfigSpec.IntValue TOTAL_DISK_MIB;
   private static final ModConfigSpec.IntValue RETENTION_DAYS;
   private static final ModConfigSpec.BooleanValue COMPACTION_ENABLED;
   private static final ModConfigSpec.IntValue PROTECTED_RECORDS;
   private static final ModConfigSpec.IntValue MAX_CELLS;
   private static final ModConfigSpec.IntValue MAX_WORK_MIB;
   private static final ModConfigSpec.BooleanValue DIAGONAL_NEIGHBORS;
   private static final ModConfigSpec.IntValue RECENT_AFTER;
   private static final ModConfigSpec.IntValue RECENT_GROUP;
   private static final ModConfigSpec.IntValue OLDER_AFTER;
   private static final ModConfigSpec.IntValue OLDER_GROUP;
   private static final ModConfigSpec.IntValue OLDEST_AFTER;
   private static final ModConfigSpec.IntValue OLDEST_GROUP;
   private static final ModConfigSpec.ConfigValue<List<? extends String>> SHAPE_PARTS;
   private static final ModConfigSpec.ConfigValue<List<? extends String>> GENERAL_PARTS;
   private static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_PROPERTIES;
   private static final ModConfigSpec.ConfigValue<String> SHAPE_TAG;
   private static final ModConfigSpec.ConfigValue<String> NO_MERGE_TAG;
   private static final ModConfigSpec.ConfigValue<List<? extends String>> MATERIAL_TAG_PREFIXES;
   private static volatile HistoryCompactionPolicy compactionPolicy = HistoryCompactionPolicy.DEFAULTS;
   private static volatile TinkerHistoryMergeLimits defaultMergeLimits = TinkerHistoryMergeLimits.DEFAULTS;

   static {
      ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
      builder.push("history");
      OWNER_DISK_MIB = builder.comment("Disk limit per player, including batch files and the index, in MiB. Restart the server after changes.")
         .worldRestart().defineInRange("playerDiskMiB", 1024, 1, 1048576);
      TOTAL_DISK_MIB = builder.comment("Disk limit for all player histories in this world, in MiB. Restart the server after changes.")
         .worldRestart().defineInRange("totalDiskMiB", 8192, 1, 1048576);
      RETENTION_DAYS = builder.comment("Remove indexed history older than this many days during startup cleanup; 0 disables expiry.")
         .worldRestart().defineInRange("retentionDays", 0, 0, 36500);
      builder.pop();
      builder.push("wrenchHistory");
      var defaults = HistoryCompactionPolicy.DEFAULTS;
      COMPACTION_ENABLED = builder.comment("Enable background compaction of micro edits, including recorded vanilla placement/breaking.").define("enabled", defaults.enabled());
      PROTECTED_RECORDS = builder.comment("Keep this many recent world records independent. At least one protects double-click restoration.")
         .defineInRange("protectedRecords", defaults.protectedRecords(), 1, 64);
      MAX_CELLS = builder.comment("Maximum accumulated cell count per worker merge.")
         .defineInRange("maxCells", defaults.maxCells(), 1, 65536);
      MAX_WORK_MIB = builder.comment("Maximum source history size per worker merge, in MiB.")
         .defineInRange("maxWorkMiB", (int)(defaults.maxBytes() / (1024 * 1024)), 1, 64);
      DIAGONAL_NEIGHBORS = builder.comment("Wrench edits only: allow edge and corner neighbors as well as face neighbors. Vanilla edits use bounds within 5 blocks.")
         .define("includeDiagonalNeighbors", defaults.includeDiagonalNeighbors());
      builder.push("defaults");
      var limits = TinkerHistoryMergeLimits.DEFAULTS;
      RECENT_AFTER = builder.comment("Compress recent history after strictly more than this many newer original actions. Player settings take precedence.")
         .defineInRange("recentAfter", limits.recentAfter(), 0, 65534);
      RECENT_GROUP = builder.comment("Combine this many compatible records into one. 1 disables this tier; each tier runs once per record.")
         .defineInRange("recentGroup", limits.recentGroup(), 1, 256);
      OLDER_AFTER = builder.comment("Older history threshold, in newer original actions. Must exceed recentAfter.")
         .defineInRange("olderAfter", limits.olderAfter(), 1, 65535);
      OLDER_GROUP = builder.comment("Records per older-history group. Previously compressed groups count as one record.")
         .defineInRange("olderGroup", limits.olderGroup(), 1, 256);
      OLDEST_AFTER = builder.comment("Oldest history threshold, in newer original actions. Must exceed olderAfter.")
         .defineInRange("oldestAfter", limits.oldestAfter(), 2, 65536);
      OLDEST_GROUP = builder.comment("Records per oldest-history group. Previously compressed groups count as one record.")
         .defineInRange("oldestGroup", limits.oldestGroup(), 1, 256);
      builder.pop();
      builder.push("grouping");
      SHAPE_PARTS = parts(builder, "shapeKeyParts", defaults.grouping().shapeParts(),
         TinkerHistoryGroupingRules.SHAPE_PARTS,
         "Shape key dimensions: material, edited_half, edit_direction, face. Empty groups all shape edits. Changes affect new records.");
      GENERAL_PARTS = parts(builder, "generalKeyParts", defaults.grouping().generalParts(),
         TinkerHistoryGroupingRules.GENERAL_PARTS,
         "Other wrench edit key dimensions: face, block_type, properties, conversion. Empty groups all other wrench edits. Vanilla edits ignore these keys.");
      IGNORED_PROPERTIES = builder.comment("Exclude these state property names from general grouping. This does not change wrench behavior.")
         .defineListAllowEmpty("ignoredProperties", List.<String>of(), value -> value instanceof String name && name.matches("[a-z0-9_]{1,64}"));
      SHAPE_TAG = builder.comment("Before or after must match this block tag for shape grouping.")
         .define("shapeEditTag", defaults.grouping().tags().shapeEdits().location().toString(), HistoryStorageConfig::validTagName);
      NO_MERGE_TAG = builder.comment("Wrench edits only: keep records independent when any changed block matches this tag before or after the edit.")
         .define("noMergeTag", defaults.grouping().tags().noMerge().location().toString(), HistoryStorageConfig::validTagName);
      MATERIAL_TAG_PREFIXES = builder.comment("Shared block tag prefixes for shape materials. The first tag in lexical order wins. Missing tags use tinker_shape families.")
         .defineListAllowEmpty("materialTagPrefixes", defaults.grouping().tags().materialPrefixes(), HistoryStorageConfig::validTagName);
      builder.pop();
      builder.pop();
      SPEC = builder.build();
   }

   private HistoryStorageConfig() {
   }

   public static long ownerDiskBytes() {
      return OWNER_DISK_MIB.get().longValue() * 1024L * 1024L;
   }

   public static long totalDiskBytes() {
      return TOTAL_DISK_MIB.get().longValue() * 1024L * 1024L;
   }

   public static int retentionDays() {
      return RETENTION_DAYS.get();
   }

   public static HistoryCompactionPolicy compactionPolicy() { return compactionPolicy; }
   public static TinkerHistoryMergeLimits defaultMergeLimits() { return defaultMergeLimits; }

   public static void onConfig(ModConfigEvent event) {
      if (event.getConfig().getSpec() != SPEC) return;
      if (!SPEC.isLoaded()) {
         compactionPolicy = HistoryCompactionPolicy.DEFAULTS;
         defaultMergeLimits = TinkerHistoryMergeLimits.DEFAULTS;
         return;
      }
      var tags = new TinkerHistoryTags(TinkerHistoryTags.blockTag(SHAPE_TAG.get()), TinkerHistoryTags.blockTag(NO_MERGE_TAG.get()),
         List.copyOf(MATERIAL_TAG_PREFIXES.get()));
      var rules = new TinkerHistoryGroupingRules(parseParts(SHAPE_PARTS.get()), parseParts(GENERAL_PARTS.get()), Set.copyOf(IGNORED_PROPERTIES.get()), tags);
      compactionPolicy = new HistoryCompactionPolicy(COMPACTION_ENABLED.get(), PROTECTED_RECORDS.get(), MAX_CELLS.get(),
         MAX_WORK_MIB.get().longValue() * 1024 * 1024, DIAGONAL_NEIGHBORS.get(), rules);
      defaultMergeLimits = new TinkerHistoryMergeLimits(RECENT_AFTER.get(), RECENT_GROUP.get(), OLDER_AFTER.get(), OLDER_GROUP.get(),
         OLDEST_AFTER.get(), OLDEST_GROUP.get());
   }

   private static ModConfigSpec.ConfigValue<List<? extends String>> parts(ModConfigSpec.Builder builder, String name,
      Set<KeyPart> defaults, Set<KeyPart> allowed, String comment) {
      var names = allowed.stream().map(part -> part.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
      return builder.comment(comment).defineListAllowEmpty(name,
         defaults.stream().map(part -> part.name().toLowerCase(Locale.ROOT)).sorted().toList(),
         value -> value instanceof String part && names.contains(part));
   }

   private static Set<KeyPart> parseParts(List<? extends String> names) {
      return names.stream().map(name -> KeyPart.valueOf(name.toUpperCase(Locale.ROOT))).collect(Collectors.toUnmodifiableSet());
   }

   private static boolean validTagName(Object value) {
      return value instanceof String name && net.minecraft.resources.ResourceLocation.tryParse(name) != null;
   }
}
