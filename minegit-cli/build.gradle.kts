plugins {
    application
}

dependencies {
    implementation(project(":core"))
    // The standalone CLI doubles as the integration harness: it drives core end-to-end through the
    // in-memory FakeWorldAdapter, which core publishes as a test fixture (Spec A §10).
    implementation(testFixtures(project(":core")))
    // JGit logs through SLF4J; bind a no-op so the CLI doesn't print "StaticLoggerBinder" noise.
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
}

application {
    mainClass.set("com.minegit.cli.Main")
}
