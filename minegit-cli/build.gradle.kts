plugins {
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.minegit.cli.Main")
}
