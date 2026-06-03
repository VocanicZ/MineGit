// Root build for the MineGit monorepo.
// No Minecraft dependencies anywhere — this is the platform-agnostic engine tree.

subprojects {
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
