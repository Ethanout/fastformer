package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

public final class FastPlaceSettings {
    private static final String KEY = FastFormer.MOD_ID;

    private boolean enabled = true;
    private LineMode lineMode = LineMode.STRAIGHT;
    private FaceMode faceMode = FaceMode.RECTANGLE;
    private VolumeMode volumeMode = VolumeMode.EXTRUDE;
    private FillMode fillMode = FillMode.WIREFRAME;

    private FastPlaceSettings() {
    }

    public static FastPlaceSettings load(ServerPlayer player) {
        FastPlaceSettings settings = new FastPlaceSettings();
        CompoundTag root = player.getPersistentData();
        if (!root.contains(KEY, CompoundTag.TAG_COMPOUND)) {
            settings.save(player);
            return settings;
        }

        CompoundTag tag = root.getCompound(KEY);
        settings.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        settings.lineMode = readEnum(tag, "lineMode", LineMode.STRAIGHT);
        settings.faceMode = readEnum(tag, "faceMode", FaceMode.RECTANGLE);
        settings.volumeMode = readEnum(tag, "volumeMode", VolumeMode.EXTRUDE);
        settings.fillMode = readEnum(tag, "fillMode", FillMode.WIREFRAME);
        return settings;
    }

    public boolean enabled() {
        return enabled;
    }

    public void toggleEnabled(ServerPlayer player) {
        enabled = !enabled;
        save(player);
    }

    public FastPlaceMode modeFor(FastPlaceStage stage) {
        return switch (stage) {
            case LINE -> lineMode;
            case FACE -> faceMode;
            case VOLUME -> volumeMode;
            case POINT -> lineMode;
        };
    }

    public LineMode lineMode() {
        return lineMode;
    }

    public FaceMode faceMode() {
        return faceMode;
    }

    public VolumeMode volumeMode() {
        return volumeMode;
    }

    public FastPlaceMode cycleMode(ServerPlayer player, FastPlaceStage stage) {
        switch (stage) {
            case LINE -> lineMode = next(lineMode);
            case FACE -> faceMode = next(faceMode);
            case VOLUME -> volumeMode = next(volumeMode);
            case POINT -> {
                return lineMode;
            }
        }
        save(player);
        return modeFor(stage);
    }

    public FillMode fillMode() {
        return fillMode;
    }

    public FillMode cycleFillMode(ServerPlayer player) {
        fillMode = next(fillMode);
        save(player);
        return fillMode;
    }

    public void save(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("enabled", enabled);
        tag.putString("lineMode", lineMode.name());
        tag.putString("faceMode", faceMode.name());
        tag.putString("volumeMode", volumeMode.name());
        tag.putString("fillMode", fillMode.name());
        player.getPersistentData().put(KEY, tag);
    }

    public static void copy(ServerPlayer from, ServerPlayer to) {
        CompoundTag root = from.getPersistentData();
        if (root.contains(KEY, CompoundTag.TAG_COMPOUND)) {
            to.getPersistentData().put(KEY, root.getCompound(KEY).copy());
        }
    }

    private static <E extends Enum<E>> E readEnum(CompoundTag tag, String key, E fallback) {
        if (!tag.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E next(E value) {
        E[] values = value.getDeclaringClass().getEnumConstants();
        return values[(value.ordinal() + 1) % values.length];
    }
}
