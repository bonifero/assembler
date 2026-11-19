package sysprog.shared.structure;

import java.util.List;

public record SymbolicName(String name, Integer address, List<Integer> pendingReferences, boolean external, String section) {
    public boolean isDefined() {
        return address != null;
    }
}
