pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "minegit"

// Platform-agnostic engine tree (Java 8, no Minecraft).
include("core")
include("protocol")
include("minegit-cli")
include("plugin")

// Architectury multiloader mod (Java 21, MC 1.21.11) — shares the engine above.
include("mod:common")
include("mod:fabric")
include("mod:neoforge")
