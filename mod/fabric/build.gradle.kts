plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("com.gradleup.shadow") version "8.3.5"
}

val minecraftVersion = property("minecraft_version") as String
val architecturyVersion = property("architectury_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
val fabricApiVersion = property("fabric_api_version") as String
val modId = property("mod_id") as String

base { archivesName.set("minegit-fabric") }
group = property("mod_group") as String
version = property("mod_version") as String

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
}

architectury {
    platformSetupLoomIde()
    fabric()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// `common` carries the shared classes onto the dev/compile classpath; `shadowBundle` collects
// everything that must be relocated + flattened into the production jar (engine + JGit + the
// transformed common classes).
val common: Configuration by configurations.creating
val shadowBundle: Configuration by configurations.creating

configurations {
    compileClasspath.get().extendsFrom(common)
    runtimeClasspath.get().extendsFrom(common)
    named("developmentFabric").get().extendsFrom(common)
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    @Suppress("UnstableApiUsage")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modApi("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    modApi("dev.architectury:architectury-fabric:$architecturyVersion")

    common(project(":mod:common", configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(":mod:common", configuration = "transformProductionFabric")) { isTransitive = false }

    // The engine + JGit (and its transitives) get relocated into the mod namespace below.
    // slf4j-api is excluded so JGit's logging binds to the slf4j Minecraft already ships; the JGit
    // SSH transport (sshd/jsch/eddsa) is excluded too — GitHub push/pull (its only consumer) is a
    // later Spec D batch, and sshd's service-provider files break NeoForge's strict JPMS scan, so
    // both loader jars stay in lockstep by dropping it here as well.
    val engineExcludes: ModuleDependency.() -> Unit = {
        exclude(group = "org.slf4j")
        exclude(group = "org.eclipse.jgit", module = "org.eclipse.jgit.ssh.apache")
        exclude(group = "org.apache.sshd")
        exclude(group = "net.i2p.crypto", module = "eddsa")
        exclude(group = "com.jcraft")
    }
    shadowBundle(project(":core"), engineExcludes)
    shadowBundle(project(":protocol"), engineExcludes)

    // Dev/GameTest runs use exploded classes, not the shadow jar, so the engine + JGit must be on
    // the run's runtime classpath too (production gets them relocated via shadowBundle). slf4j is
    // excluded so JGit binds to the slf4j Minecraft already ships in dev rather than duplicating it.
    runtimeOnly(project(":core"), engineExcludes)
    runtimeOnly(project(":protocol"), engineExcludes)
}

// Headless GameTest run (Spec D §6, issue #64): boots a dedicated server with Fabric's GameTest
// runner enabled, runs every @GameTest, writes a JUnit report, and exits non-zero on failure.
// Invoke with `./gradlew :mod:fabric:runGametest`.
loom {
    // Dirty-tracking mixin (Spec E task 4): architectury-loom 1.14 ships the mixin AP off by default,
    // so re-enable it (useLegacyMixinAp) to generate the refmap that maps named → intermediary/SRG at
    // runtime on both the dev (named) and remapped jars.
    mixin {
        useLegacyMixinAp.set(true)
        defaultRefmapName.set("minegit.refmap.json")
    }
    runs {
        create("gametest") {
            server()
            ideConfigGenerated(false)
            vmArg("-Dfabric-api.gametest")
            vmArg("-Dfabric-api.gametest.report-file=${layout.buildDirectory.get().asFile}/junit.xml")
            runDir("build/gametest")
        }
    }
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand("version" to project.version, "mod_id" to modId)
        }
    }

    shadowJar {
        configurations = listOf(shadowBundle)
        archiveClassifier.set("dev-shadow")
        // Relocate JGit + engine + JGit's transitives that Minecraft also ships, so the bundled
        // copies never collide with Minecraft's own log4j/slf4j/commons on the global classpath.
        relocate("org.eclipse.jgit", "net.rainbowcreation.vocanicz.minegit.mod.libs.jgit")
        relocate("org.apache.sshd", "net.rainbowcreation.vocanicz.minegit.mod.libs.sshd")
        relocate("org.apache.commons", "net.rainbowcreation.vocanicz.minegit.mod.libs.commons")
        relocate("com.googlecode.javaewah", "net.rainbowcreation.vocanicz.minegit.mod.libs.javaewah")
        relocate("com.jcraft.jsch", "net.rainbowcreation.vocanicz.minegit.mod.libs.jsch")
        relocate("net.rainbowcreation.vocanicz.minegit.core", "net.rainbowcreation.vocanicz.minegit.mod.libs.core")
        relocate("net.rainbowcreation.vocanicz.minegit.protocol", "net.rainbowcreation.vocanicz.minegit.mod.libs.protocol")
    }

    remapJar {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        dependsOn(shadowJar)
        archiveClassifier.set("")
    }
}
