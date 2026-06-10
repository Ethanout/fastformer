package io.github.fastformer.fastplace;

public enum FastPlaceStage {
    POINT("Point"),
    LINE("Line"),
    FACE("Face"),
    VOLUME("Volume");

    private final String displayName;

    FastPlaceStage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
