package com.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;

class TokenCredentialTest {

    private static URIish uri() throws Exception {
        return new URIish("https://github.com/owner/repo.git");
    }

    @Test
    void credentialsProvider_usesXAccessTokenUsernameAndTokenPassword() throws Exception {
        Credential cred = new TokenCredential("ghp_secret123");
        CredentialsProvider provider = cred.credentialsProvider();

        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(provider.get(uri(), username, password), "provider supplies username + password");

        assertEquals("x-access-token", username.getValue());
        assertArrayEquals("ghp_secret123".toCharArray(), password.getValue());
    }

    @Test
    void transportConfigCallback_isNull_becauseTokenAuthNeedsNoSshTuning() {
        assertNull(new TokenCredential("tok").transportConfigCallback());
    }

    @Test
    void blankToken_isRejected_soNoEmptyCredentialReachesTheWire() {
        assertThrows(IllegalArgumentException.class, () -> new TokenCredential("   "));
    }

    @Test
    void nullToken_isRejected() {
        assertThrows(NullPointerException.class, () -> new TokenCredential(null));
    }
}
