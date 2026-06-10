package io.github.fastformer.fastplace;

public enum LineMode implements FastPlaceMode {
    STRAIGHT("Straight"),
    PLANAR("Planar"),
    VIEW_PLANE("View plane"),
    SPATIAL("Spatial");

    private final String displayName;

    LineMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public FastPlaceStage stage() {
        return FastPlaceStage.LINE;
    }

    @Override
    public String displayName() {
        return displayName;
    }
}
