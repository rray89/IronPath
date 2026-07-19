// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
}

sonar {
    properties {
        property("sonar.projectName", "IronPath")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.kotlin.source.version", "2.3")
        property("sonar.exclusions", "**/*.otf,**/*.webp")
        property(
            "sonar.coverage.exclusions",
            listOf(
                    "**/R.*",
                    "**/BuildConfig.*",
                    "**/Manifest*.*",
                    "**/*_Factory.*",
                    "**/*_MembersInjector.*",
                    "**/*_Impl.*",
                    "**/Hilt_*.*",
                    "**/Dagger*.*",
                    "**/hilt_aggregated_deps/**",
                    "**/benchmark/**",
                )
                .joinToString(","),
        )
    }
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**/*.kt")
        targetExclude(".claude/worktrees/**/*.kt")
        ktfmt().kotlinlangStyle()
    }
    kotlinGradle {
        target("**/*.kts")
        targetExclude("**/build/**/*.kts")
        targetExclude(".claude/worktrees/**/*.kts")
        ktfmt().kotlinlangStyle()
    }
}
