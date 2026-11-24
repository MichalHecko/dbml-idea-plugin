plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // ponytail: builds against the locally installed IDEA (2026.3, Java 25 bytecode); use intellijIdea("2026.3") on CI
        local("${System.getProperty("user.home")}/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate")
        bundledModule("intellij.libraries.batik") // SVGGraphics2D for SVG export
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    buildSearchableOptions = false // no settings pages
    pluginVerification {
        ides { local("${System.getProperty("user.home")}/.local/share/JetBrains/Toolbox/apps/intellij-idea-ultimate") }
    }
}
