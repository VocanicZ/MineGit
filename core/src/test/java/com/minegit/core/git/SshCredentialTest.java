package com.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshCredentialTest {

    @Test
    void construction_buildsSshSessionFactoryBoundToTheKey_noCredentialsProvider(@TempDir Path dir) {
        Path key = dir.resolve("id_ed25519");
        SshCredential cred = new SshCredential(key);

        assertNull(cred.credentialsProvider(), "SSH auth uses no HTTP credentials provider");
        assertNotNull(cred.transportConfigCallback(), "SSH auth installs a transport-config callback");
        assertNotNull(cred.sessionFactory(), "an SshSessionFactory is built at construction");
        assertEquals(key.toAbsolutePath(), cred.getPrivateKeyPath(), "key path is retained, absolute");
        assertFalse(cred.hasPassphrase(), "no passphrase supplied");
    }

    @Test
    void construction_withPassphrase_recordsThatAPassphraseIsPresent(@TempDir Path dir) {
        SshCredential cred = new SshCredential(dir.resolve("id_rsa"), "hunter2");
        assertTrue(cred.hasPassphrase());
        assertNotNull(cred.sessionFactory());
    }

    @Test
    void transportConfigCallback_ignoresNonSshTransport_soHttpCloneIsUnaffected(@TempDir Path dir) {
        // The callback only touches SshTransport; passing anything else (here null) is a safe no-op.
        TransportConfigCallback callback = new SshCredential(dir.resolve("id_rsa")).transportConfigCallback();
        callback.configure(null);
    }

    @Test
    void nullKey_isRejected() {
        assertThrows(NullPointerException.class, () -> new SshCredential(null));
    }
}
