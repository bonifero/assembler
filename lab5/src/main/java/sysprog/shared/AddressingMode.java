package sysprog.shared;

public enum AddressingMode {
    MIXED("Смешанная адресация", true, true),
    DIRECT_ONLY("Только прямая адресация", true, false),
    RELATIVE_ONLY("Только относительная адресация", false, true);

    private final String title;
    private final boolean directAllowed;
    private final boolean relativeAllowed;

    AddressingMode(String title, boolean directAllowed, boolean relativeAllowed) {
        this.title = title;
        this.directAllowed = directAllowed;
        this.relativeAllowed = relativeAllowed;
    }

    public boolean isDirectAllowed() {
        return directAllowed;
    }

    public boolean isRelativeAllowed() {
        return relativeAllowed;
    }

    @Override
    public String toString() {
        return title;
    }
}
