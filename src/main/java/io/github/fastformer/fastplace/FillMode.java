package io.github.fastformer.fastplace;

public enum FillMode {
    WIREFRAME("Wireframe"),
    HOLLOW("Hollow"),
    SOLID("Solid");

    private final String displayName;

    FillMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
