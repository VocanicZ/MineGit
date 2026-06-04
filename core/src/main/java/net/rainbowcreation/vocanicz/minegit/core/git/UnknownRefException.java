package net.rainbowcreation.vocanicz.minegit.core.git;

/**
 * Thrown when a revision string cannot be resolved to an object in the repository — e.g. a branch
 * name with no such branch, or a bogus commit id. Surfaces unresolvable refs loudly instead of
 * letting them collapse to an empty tree and produce a misleading "everything removed" diff.
 *
 * <p>No Minecraft dependencies.
 */
public final class UnknownRefException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String ref;

    public UnknownRefException(String ref) {
        super("unknown ref '" + ref + "'");
        this.ref = ref;
    }

    /** The revision string that could not be resolved. */
    public String getRef() {
        return ref;
    }
}
