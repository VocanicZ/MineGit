package com.minegit.core.git;

import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;

/**
 * How a MineGit remote operation (fetch / push / clone) authenticates to its git remote.
 *
 * <p>A {@code Credential} contributes the two knobs every JGit {@link TransportCommand} exposes:
 *
 * <ul>
 *   <li>a {@link CredentialsProvider} — the username/password (or token) channel used by the HTTP(S)
 *       transport, and
 *   <li>a {@link TransportConfigCallback} — the hook used to install a custom
 *       {@link org.eclipse.jgit.transport.SshSessionFactory} for the SSH transport.
 * </ul>
 *
 * <p>Either may be {@code null} when an implementation does not need it (e.g. token auth needs no SSH
 * tuning; SSH auth needs no credentials provider). {@link #applyTo} wires both onto a command in one
 * call so callers never touch the JGit knobs directly.
 *
 * <p><strong>No secret literals live in {@code core}.</strong> The token / key material is always
 * supplied by the caller; the implementations here only shape it into JGit's transport contract. No
 * Minecraft dependencies.
 */
public interface Credential {

    /**
     * The credentials provider for the HTTP(S) transport, or {@code null} if this credential does not
     * authenticate over HTTP(S) (for example {@link SshCredential} and {@link DefaultGitCredential}).
     */
    CredentialsProvider credentialsProvider();

    /**
     * The transport-config callback that installs a custom SSH session factory, or {@code null} if
     * this credential does not customize the SSH transport (for example {@link TokenCredential} and
     * {@link DefaultGitCredential}).
     */
    TransportConfigCallback transportConfigCallback();

    /**
     * Wires this credential's {@linkplain #credentialsProvider() provider} and
     * {@linkplain #transportConfigCallback() transport-config callback} onto {@code command}. A
     * {@code null} contribution clears the corresponding knob, which is the JGit default, so a
     * {@link DefaultGitCredential} leaves the command relying on git/netrc config.
     */
    default void applyTo(TransportCommand<?, ?> command) {
        command.setCredentialsProvider(credentialsProvider());
        command.setTransportConfigCallback(transportConfigCallback());
    }
}
