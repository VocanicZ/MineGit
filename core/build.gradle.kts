plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    // JGit 5.13.x is the last Java-8-compatible line.
    api("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    // Apache MINA sshd transport for SshCredential (also Java-8-compatible at 5.13.x).
    api("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:5.13.3.202401111512-r")
}
