package net.rainbowcreation.vocanicz.minegit.core.git;

/**
 * Thrown by {@link MineGitRepo#planCheckout(String, boolean, boolean)} when the checkout target is the
 * <strong>empty initial repository state</strong> — the metadata-only <em>root</em> commit (no parent)
 * created by {@link MineGitRepo#init}, whose tree contains no {@code .mgc} chunk blobs. Checking it out
 * would compute {@code HEAD → target} as "remove every chunk" and silently empty the live world, so
 * MineGit refuses it outright (even with {@code force}, which only overrides the dirty-guard, not this
 * one). A deliberately-empty <em>later</em> commit is not a root and remains checkout-able.
 *
 * <p>No Minecraft dependencies.
 */
public final class EmptyTargetCheckoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmptyTargetCheckoutException(String message) {
        super(message);
    }
}
