pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://www.jetbrains.com/intellij-repository/releases") {
            content { includeModule("com.jetbrains.intellij.platform", "icons") }
        }
    }
}

rootProject.name = "voxarium-studio-desktop"
