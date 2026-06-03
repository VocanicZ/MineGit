package com.minegit.core.git;

import java.util.Objects;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * HTTPS token authentication. Maps a personal-access / installation token onto JGit's basic-auth
 * channel as {@link UsernamePasswordCredentialsProvider}{@code ("x-access-token", token)} — the
 * username GitHub (and most forges) expect when the password slot carries a token.
 *
 * <p>Customizes no SSH transport, so {@link #transportConfigCallback()} is {@code null}. The token is
 * supplied by the caller; no secret literal lives in {@code core}.
 */
public final class TokenCredential implements Credential {

    /** Fixed username GitHub expects when the password slot carries an access token. */
    public static final String TOKEN_USERNAME = "x-access-token";

    private final String token;

    /**
     * @param token a non-blank access token; the value is the caller's secret and is never logged.
     * @throws NullPointerException if {@code token} is {@code null}
     * @throws IllegalArgumentException if {@code token} is blank
     */
    public TokenCredential(String token) {
        Objects.requireNonNull(token, "token");
        if (token.trim().isEmpty()) {
            throw new IllegalArgumentException("token must not be blank");
        }
        this.token = token;
    }

    /** Convenience factory mirroring the constructor. */
    public static TokenCredential of(String token) {
        return new TokenCredential(token);
    }

    @Override
    public CredentialsProvider credentialsProvider() {
        return new UsernamePasswordCredentialsProvider(TOKEN_USERNAME, token);
    }

    @Override
    public TransportConfigCallback transportConfigCallback() {
        return null;
    }
}
