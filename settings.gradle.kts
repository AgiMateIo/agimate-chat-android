pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // SDK пуш-уведомлений RuStore в Maven Central не публикуется — только сюда.
        maven("https://nexus-external.rustore.ru/repository/maven-rustore-exposed") {
            content { includeGroupByRegex("ru\\.rustore.*") }
        }
    }
}

rootProject.name = "AgiMate"
include(":app")
