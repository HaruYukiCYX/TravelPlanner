pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // ✅ 高德仓库 正确Kotlin语法 + 最新有效地址
        maven("https://developer.amap.com/android-maven/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ✅ 高德仓库 必须在这里也加一次，缺一不可
        maven("https://developer.amap.com/android-maven/")
    }
}

rootProject.name = "TravelPlanner" // 这个是你的项目名，不用改
include(":app")