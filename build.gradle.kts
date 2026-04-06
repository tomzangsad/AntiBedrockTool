plugins {
    java
}

subprojects {
    apply(plugin = "java")

    group = "com.kaizer.antibt"
    version = "2.0.0"

    java {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://repo.opencollab.dev/main/")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}
