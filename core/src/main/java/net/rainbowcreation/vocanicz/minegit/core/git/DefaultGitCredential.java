package net.rainbowcreation.vocanicz.minegit.core.git;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;

/**
 * No-op credential: contributes neither a {@link CredentialsProvider} nor a
 * {@link TransportConfigCallback}, so a remote operation falls back entirely on the host's git
 * configuration — the credential helper, {@code ~/.netrc}, the SSH agent, and {@code ~/.ssh/config}.
 *
 * <p>Stateless, so a single shared {@link #INSTANCE} is used.
 */
public final class DefaultGitCredential implements Credential {

    /** The shared, stateless instance. */
    public static final DefaultGitCredential INSTANCE = new DefaultGitCredential();

    public DefaultGitCredential() {}

    @Override
    public CredentialsProvider credentialsProvider() {
        return null;
    }

    @Override
    public TransportConfigCallback transportConfigCallback() {
        return null;
    }
}
