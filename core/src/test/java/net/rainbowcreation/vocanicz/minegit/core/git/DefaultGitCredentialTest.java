package net.rainbowcreation.vocanicz.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class DefaultGitCredentialTest {

    @Test
    void contributesNothing_soOpsFallBackToGitAndNetrcConfig() {
        Credential cred = DefaultGitCredential.INSTANCE;
        assertNull(cred.credentialsProvider(), "no credentials provider — defer to git/netrc");
        assertNull(cred.transportConfigCallback(), "no SSH tuning — defer to git/netrc");
    }

    @Test
    void instance_isShared() {
        assertSame(DefaultGitCredential.INSTANCE, DefaultGitCredential.INSTANCE);
    }
}
