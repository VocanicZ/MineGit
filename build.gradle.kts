// Root build for the MineGit monorepo.
//
// Two trees live here:
//   * the engine — `core` / `protocol` / `minegit-cli` / `plugin` — Java 8 bytecode, NO Minecraft.
//   * the Architectury mod — `mod:common` / `mod:fabric` / `mod:neoforge` — Java 21, MC 1.21.11.
// The Architectury subprojects configure themselves (loom + architectury-plugin); the shared
// engine config below applies ONLY to the engine projects so it never fights loom's own setup.

plugins {
    // On the classpath for the mod subprojects; never applied to the root or the engine tree.
    id("architectury-plugin") version "3.5.166" apply false
    id("dev.architectury.loom") version "1.14.475" apply false
}

// Engine projects: Java 8, JUnit 5, Maven Central only. The mod subprojects are excluded.
val engineProjects = setOf("core", "protocol", "minegit-cli", "plugin")

subprojects {
    if (name !in engineProjects) return@subprojects

    apply(plugin = "java")

    group = "com.minegit"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    // Hard constraint: the Spigot 1.8.8 plugin runs on Java 8, so core/protocol emit
    // Java 8 bytecode. The build toolchain may be a newer JDK (cross-compilation).
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.2"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        // We intentionally target Java 8 bytecode; silence the "source/target 8 is obsolete" noise.
        options.compilerArgs.add("-Xlint:-options")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
