plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("com.gradleup.shadow") version "8.3.5"
}

val minecraftVersion = property("minecraft_version") as String
val architecturyVersion = property("architectury_version") as String
val neoforgeVersion = property("neoforge_version") as String
val modId = property("mod_id") as String

base { archivesName.set("minegit-neoforge") }
group = property("mod_group") as String
version = property("mod_version") as String

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
}

architectury {
    platformSetupLoomIde()
    neoForge()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

val common: Configuration by configurations.creating
val shadowBundle: Configuration by configurations.creating

configurations {
    compileClasspath.get().extendsFrom(common)
    runtimeClasspath.get().extendsFrom(common)
    named("developmentNeoForge").get().extendsFrom(common)
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    @Suppress("UnstableApiUsage")
    mappings(loom.officialMojangMappings())

    "neoForge"("net.neoforged:neoforge:$neoforgeVersion")
    modApi("dev.architectury:architectury-neoforge:$architecturyVersion")

    common(project(":mod:common", configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(":mod:common", configuration = "transformProductionNeoForge")) { isTransitive = false }

    // The engine + JGit, relocated into the mod namespace below. slf4j-api is excluded so JGit
    // binds to Minecraft's own slf4j; the JGit SSH transport (+ its sshd/jsch/eddsa stack) is
    // excluded too — GitHub push/pull (the only SSH consumer) is deferred to a later Spec D batch,
    // and sshd's service-provider files otherwise break NeoForge's strict JPMS module descriptor.
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

// Headless GameTest run (Spec D §6, issue #64): boots NeoForge's dedicated GameTest server, which
// runs every registered test then exits non-zero on failure. Invoke with
// `./gradlew :mod:neoforge:runGameTestServer`. Mirrors NeoForge's userdev `gameTestServer` run.
loom {
    // Dirty-tracking mixin (Spec E task 4): architectury-loom 1.14 ships the mixin AP off by default,
    // so re-enable it (useLegacyMixinAp) to generate the refmap that maps named → intermediary/SRG at
    // runtime on both the dev (named) and remapped jars.
    mixin {
        useLegacyMixinAp.set(true)
        defaultRefmapName.set("minegit.refmap.json")
    }
    runs {
        create("gameTestServer") {
            server()
            mainClass.set("net.neoforged.fml.startup.GameTestServer")
            property("neoforge.enableGameTest", "true")
            property("neoforge.enabledGameTestNamespaces", modId)
            vmArgs(
                "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming",
            )
            runDir("build/gametest")
        }
    }
}

tasks {
    processResources {
        inputs.property("version", project.version)
        filesMatching("META-INF/neoforge.mods.toml") {
            expand("version" to project.version, "mod_id" to modId)
        }
    }

    shadowJar {
        configurations = listOf(shadowBundle)
        archiveClassifier.set("dev-shadow")
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
