plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val minecraftVersion = property("minecraft_version") as String
val architecturyVersion = property("architectury_version") as String
val fabricLoaderVersion = property("fabric_loader_version") as String
val enabledPlatforms = (property("enabled_platforms") as String).split(",")

base { archivesName.set("minegit-common") }
group = property("mod_group") as String
version = property("mod_version") as String

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.architectury.dev/") { name = "Architectury" }
}

architectury {
    // The common module compiles against vanilla MC + Architectury only; `@ExpectPlatform`
    // bytecode is transformed per-loader for the fabric/neoforge consumers.
    common(enabledPlatforms)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    @Suppress("UnstableApiUsage")
    mappings(loom.officialMojangMappings())

    // fabric-loader here only supplies the @Environment annotations + mixin deps the common
    // source set links against — NOT a runtime dependency on Fabric internals.
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modApi("dev.architectury:architectury:$architecturyVersion")

    // The platform-agnostic MineGit engine, consumed as ordinary deps. Each loader jar
    // relocates + bundles these (and JGit) via shadow — see mod/fabric + mod/neoforge.
    implementation(project(":core"))
    implementation(project(":protocol"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The core engine's in-memory FakeWorldAdapter drives headless init/status/log tests for the
    // command service, so the git path is exercised without booting a Minecraft server.
    testImplementation(testFixtures(project(":core")))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
