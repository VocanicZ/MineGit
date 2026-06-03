package com.minegit.core.git;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;

/**
 * SSH public-key authentication. Binds an {@link SshdSessionFactory} (Apache MINA sshd) to a single
 * private-key path so a fetch / push / clone over {@code ssh://} or {@code git@host:repo} uses that
 * key instead of scanning {@code ~/.ssh}. An optional passphrase decrypts an encrypted key.
 *
 * <p>The factory is built once at construction and installed via {@link #transportConfigCallback()},
 * which sets it on the {@link SshTransport} for an op (and ignores any non-SSH transport). Customizes
 * no HTTP(S) credentials, so {@link #credentialsProvider()} is {@code null}.
 *
 * <p>The key path and passphrase are supplied by the caller; no secret literal lives in {@code core}.
 * No actual SSH connection is opened until a transport op runs.
 */
public final class SshCredential implements Credential {

    private final Path privateKey;
    private final char[] passphrase;
    private final SshdSessionFactory sessionFactory;

    /** SSH credential with an unencrypted key. */
    public SshCredential(Path privateKey) {
        this(privateKey, (char[]) null);
    }

    /** SSH credential with a passphrase-encrypted key (a {@code null}/blank passphrase means none). */
    public SshCredential(Path privateKey, String passphrase) {
        this(privateKey, passphrase == null || passphrase.isEmpty() ? null : passphrase.toCharArray());
    }

    /**
     * @param privateKey path to the private key; resolved to an absolute path and retained.
     * @param passphrase the key passphrase, or {@code null} for an unencrypted key. Defensively
     *     copied; the caller may wipe its own array afterward.
     * @throws NullPointerException if {@code privateKey} is {@code null}
     */
    public SshCredential(Path privateKey, char[] passphrase) {
        Objects.requireNonNull(privateKey, "privateKey");
        this.privateKey = privateKey.toAbsolutePath();
        this.passphrase = passphrase == null ? null : passphrase.clone();
        this.sessionFactory = buildFactory(this.privateKey, this.passphrase);
    }

    private static SshdSessionFactory buildFactory(Path key, char[] passphrase) {
        Path parent = key.getParent();
        File home = FS.DETECTED.userHome();
        File sshDir = parent != null ? parent.toFile() : home;
        SshdSessionFactoryBuilder builder = new SshdSessionFactoryBuilder()
            .setHomeDirectory(home)
            .setSshDirectory(sshDir)
            .setPreferredAuthentications("publickey")
            .setDefaultIdentities(ignoredSshDir -> Collections.singletonList(key));
        if (passphrase != null) {
            char[] pp = passphrase.clone();
            builder.setKeyPasswordProvider(ignoredProvider -> fixedPassphraseProvider(pp));
        }
        return builder.build(null);
    }

    private static KeyPasswordProvider fixedPassphraseProvider(char[] passphrase) {
        return new KeyPasswordProvider() {
            @Override
            public char[] getPassphrase(URIish uri, int attempt) {
                return passphrase.clone();
            }

            @Override
            public void setAttempts(int maxNumberOfAttempts) {
                // Single fixed passphrase; attempt budget is irrelevant.
            }

            @Override
            public boolean keyLoaded(URIish uri, int attempt, Exception error) {
                // A wrong passphrase is fatal — do not retry with the same fixed value.
                return false;
            }
        };
    }

    @Override
    public CredentialsProvider credentialsProvider() {
        return null;
    }

    @Override
    public TransportConfigCallback transportConfigCallback() {
        SshdSessionFactory factory = sessionFactory;
        return (Transport transport) -> {
            if (transport instanceof SshTransport) {
                ((SshTransport) transport).setSshSessionFactory(factory);
            }
        };
    }

    /** The SSH session factory this credential installs. Never {@code null}. */
    public SshdSessionFactory sessionFactory() {
        return sessionFactory;
    }

    /** The absolute private-key path this credential is bound to. */
    public Path getPrivateKeyPath() {
        return privateKey;
    }

    /** Whether a passphrase was supplied for the key. */
    public boolean hasPassphrase() {
        return passphrase != null;
    }
}
