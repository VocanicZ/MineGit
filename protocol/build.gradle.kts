plugins {
    `java-library`
}

dependencies {
    // The DiffPayload wire codec serializes core's WorldDiff model (Spec A §11).
    // No Minecraft imports — core is the platform-agnostic engine tree.
    api(project(":core"))
}
