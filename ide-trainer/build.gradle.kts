plugins {
    // Must be able to read the Kotlin metadata in the IDE's own stdlib
    // (2026.2 ships metadata 2.4.0), otherwise every platform call is
    // "unresolved reference".
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.tanvoid0"
version = "0.15.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("idePath"))
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Progress is kept in SQLite. The IDE bundles its own sqlite module, but
    // every class in it is @ApiStatus.Internal and verifyPlugin fails the
    // build over it, so this uses the ordinary public driver instead.
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

intellijPlatform {
    // ./gradlew verifyPlugin — binary compatibility against the IDE we target
    pluginVerification {
        ides { local(providers.gradleProperty("idePath")) }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}
