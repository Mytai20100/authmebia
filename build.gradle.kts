plugins {
    id("java-library")
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // AuthMe 6.0.0 split into multiple modules; the core API (AuthMeApi +
    // fr.xephi.authme.events.*) now lives in authme-core, published to the
    // CodeMC repository. The old monolithic fr.xephi:authme artifact is gone.
    compileOnly("fr.xephi:authme-core:6.0.0-SNAPSHOT") {
        isTransitive = false
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        relocate("okhttp3", "com.authmebia.libs.okhttp3")
        relocate("okio", "com.authmebia.libs.okio")
        relocate("kotlin", "com.authmebia.libs.kotlin")
        relocate("com.google.gson", "com.authmebia.libs.gson")
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
