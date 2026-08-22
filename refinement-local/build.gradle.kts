plugins {
    id("voicecomposer.jvm-module")
}

dependencies {
    api(project(":core"))
    api(project(":commands"))
    api(project(":refinement-api"))
}
