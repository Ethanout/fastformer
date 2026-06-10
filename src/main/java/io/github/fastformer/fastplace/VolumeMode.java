package io.github.fastformer.fastplace;

public enum VolumeMode implements FastPlaceMode {
    EXTRUDE("Extrude"),
    POLYHEDRON("Polyhedron");

    private final String displayName;

    VolumeMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public FastPlaceStage stage() {
        return FastPlaceStage.VOLUME;
    }

    @Override
    public String displayName() {
        return displayName;
    }
}
