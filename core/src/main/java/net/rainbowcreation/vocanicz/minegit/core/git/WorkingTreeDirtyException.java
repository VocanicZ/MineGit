package net.rainbowcreation.vocanicz.minegit.core.git;

/**
 * Thrown by {@link MineGitRepo#checkout(String)} (and, later, {@code pull}) when the live world
 * differs from {@code HEAD} — i.e. {@link net.rainbowcreation.vocanicz.minegit.core.diff.WorldDiffer#diffWorkingTree} is
 * non-empty — and {@code force} was not requested. Mirrors git's refusal to clobber uncommitted work.
 *
 * <p>No Minecraft dependencies.
 */
public final class WorkingTreeDirtyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkingTreeDirtyException(String message) {
        super(message);
    }
}
