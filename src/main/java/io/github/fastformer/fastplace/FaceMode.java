package io.github.fastformer.fastplace;

public enum FaceMode implements FastPlaceMode {
    RECTANGLE("Rectangle"),
    PARALLELOGRAM("Parallelogram"),
    POLYGON("Polygon");

    private final String displayName;

    FaceMode(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public FastPlaceStage stage() {
        return FastPlaceStage.FACE;
    }

    @Override
    public String displayName() {
        return displayName;
    }
}
