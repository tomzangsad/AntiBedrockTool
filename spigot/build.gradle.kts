plugins {
    id("com.gradleup.shadow") version "9.4.1"
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.geysermc.geyser:api:2.4.2-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.3-SNAPSHOT")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("AntiBedrockTool-Spigot")
    relocate("com.kaizer.antibt", "com.kaizer.antibt")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
