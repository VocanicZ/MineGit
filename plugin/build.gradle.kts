plugins {
    java
    // Maintained fork of the shadow plugin (johnrengelman is unmaintained on Gradle 8.x).
    id("com.gradleup.shadow") version "8.3.5"
    // jpenilla's run-task: spins up a modern Paper server for in-game testing of a 1.8.8-API plugin.
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

repositories {
    // Spigot API snapshots live in Spigot's own Nexus, not Maven Central.
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "spigot-snapshots"
    }
    // Paper repo provides the run-paper download metadata and Sonatype hosts the sonatype snapshots Spigot pulls.
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "sonatype"
    }
}

dependencies {
    // Compile against the 1.8.8 API surface only; the server provides Bukkit at runtime.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")

    // The engine — shaded + relocated into the plugin jar so it runs 1.8 -> latest with no clashes.
    implementation(project(":core"))
    implementation(project(":protocol"))
}

// processResources expands ${version} in plugin.yml so the in-game version matches the build.
tasks.named<ProcessResources>("processResources") {
    val tokens = mapOf("version" to project.version.toString())
    inputs.properties(tokens)
    filesMatching("plugin.yml") {
        expand(tokens)
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // Relocate the engine + JGit out of their canonical packages so the single plugin jar never
    // collides with another plugin (or the server) that ships a different JGit / shaded copy.
    relocate("org.eclipse.jgit", "com.minegit.plugin.libs.jgit")
    relocate("com.minegit.core", "com.minegit.plugin.libs.core")
    relocate("com.minegit.protocol", "com.minegit.plugin.libs.protocol")
}

// `build` should produce the runnable, relocated plugin jar.
tasks.named("build") {
    dependsOn("shadowJar")
}

// runServer: modern Paper, so the in-game smoke test exercises the Modern (reflection) BlockBridge path.
tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("1.20.4")
}
