/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlinx.kover)
}

val projectMinSdk: String by project
val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project
val projectVersionNameSuffix = projectVersionName.substringAfter("-", "").let { suffix ->
    if (suffix.isNotEmpty()) {
        "-$suffix"
    } else {
        suffix
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-jvm-default=enable",
            "-Xwhen-guards",
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
            "-XXLanguage:+LocalTypeAliases",
        ))
    }
}

configure<ApplicationExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = projectCompileSdk.toInt()
    buildToolsVersion = tools.versions.buildTools.get()
    ndkVersion = tools.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        // KalamBoard identity. The Kotlin namespace stays dev.patrickgold.florisboard (internal,
        // never user-visible) so the fork remains mergeable with upstream.
        applicationId = "org.kalamboard.keyboard"
        minSdk = projectMinSdk.toInt()
        targetSdk = projectTargetSdk.toInt()
        versionCode = projectVersionCode.toInt()
        versionName = projectVersionName.substringBefore("-")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${getGitCommitHash().get()}\"")
        // OFFLINE BUILD: FLADDONS_API_VERSION / FLADDONS_STORE_URL removed together with the
        // addons-store button and the extension update checker that consumed them.

        sourceSets {
            maybeCreate("main").apply {
                assets.directories += "src/main/assets"
            }
        }
    }

    bundle {
        language {
            // We disable language split because FlorisBoard does not use
            // runtime Google Play Service APIs and thus cannot dynamically
            // request to download the language resources for a specific locale.
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // KalamBoard release signing. The keystore lives OUTSIDE the repo (../keystore/) so it can
    // never be committed; if it is absent the release build stays unsigned instead of failing,
    // so CI/other machines can still compile.
    val keystorePropsFile = rootProject.file("../keystore/keystore.properties")
    if (keystorePropsFile.exists()) {
        val keystoreProps = Properties()
        keystorePropsFile.inputStream().use { stream -> keystoreProps.load(stream) }
        val storeFileName = keystoreProps.getProperty("storeFile")
        if (storeFileName == null || keystoreProps.getProperty("storePassword") == null ||
            keystoreProps.getProperty("keyAlias") == null || keystoreProps.getProperty("keyPassword") == null
        ) {
            logger.warn("KalamBoard: keystore.properties is incomplete (needs storeFile/storePassword/keyAlias/keyPassword) — release APK will be UNSIGNED.")
        } else {
            signingConfigs.create("kalamboardRelease") {
                storeFile = rootProject.file("../keystore/" + File(storeFileName).name)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    } else {
        logger.warn("KalamBoard: ../keystore/keystore.properties not found — release APK will be UNSIGNED.")
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug+${getGitCommitHash(short = true).get()}"

            isDebuggable = true
            isJniDebuggable = false
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
        }

        named("release") {
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfigs.findByName("kalamboardRelease")?.let { signingConfig = it }
        }

        create("benchmark") {
            initWith(getByName("release"))

            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench+${getGitCommitHash(short = true).get()}"

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    lint {
        baseline = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// OFFLINE BUILD GUARD
//
// This fork must never be able to talk to the network. Removing the calls is not enough on its
// own: any dependency (direct or transitive) may inject <uses-permission android:name="INTERNET"/>
// into the merged manifest, and once that permission is present the whole guarantee is gone
// silently. This task therefore inspects the *merged* manifest of every variant and fails the
// build if a network permission reappears, plus it scans the version catalog for well-known
// networking libraries so they are caught at review time rather than at runtime.
// ---------------------------------------------------------------------------------------------

abstract class VerifyOfflineBuildTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionCatalog: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()

        val manifest = mergedManifest.get().asFile.readText()
        val forbiddenPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.CHANGE_WIFI_STATE",
        )
        for (permission in forbiddenPermissions) {
            if (manifest.contains(permission)) {
                violations += "merged manifest declares $permission"
            }
        }
        if (manifest.contains("android:allowBackup=\"true\"")) {
            violations += "merged manifest re-enables android:allowBackup, which would upload the " +
                "user dictionary and settings through the platform backup transport"
        }

        val catalog = versionCatalog.get().asFile.readText()
        val forbiddenModules = listOf(
            "com.squareup.okhttp3", "com.squareup.retrofit2", "io.ktor",
            "com.android.volley", "coil-network", "com.google.firebase",
            "io.sentry", "ch.acra", "com.google.android.gms",
        )
        for (module in forbiddenModules) {
            if (catalog.contains(module)) {
                violations += "version catalog references networking library '$module'"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("OFFLINE BUILD GUARD FAILED — this build is required to have no network access.")
                    violations.forEach { appendLine("  - $it") }
                    appendLine("Revert the change or, if it is genuinely intended, update the guard in app/build.gradle.kts.")
                }
            )
        }

        reportFile.get().asFile.writeText(
            "offline build guard passed\nno network permission in merged manifest\nno networking library in version catalog\n"
        )
    }
}

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val guardTask = tasks.register<VerifyOfflineBuildTask>("verify${variantName}OfflineBuild") {
            group = "verification"
            description = "Fails the build if $variantName regained any network capability."
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            versionCatalog.set(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml"))
            reportFile.set(layout.buildDirectory.file("reports/offline-guard/$variantName.txt"))
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            dependsOn(guardTask)
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("src/main/config")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()
}

kover {
    useJacoco()
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    // testImplementation(composeBom)
    // androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.window.core)
    implementation(libs.cache4k)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)

    implementation(projects.lib.android)
    implementation(projects.lib.color)
    implementation(projects.lib.compose)
    implementation(projects.lib.kotlin)
    // OFFLINE BUILD (phase 6): projects.lib.native unhooked together with the module, see settings.gradle.kts.
    implementation(projects.lib.snygg)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

fun getGitCommitHash(short: Boolean = false): Provider<String> {
    if (!File(".git").exists()) {
        return providers.provider { "null" }
    }

    val execProvider = providers.exec {
        if (short) {
            commandLine("git", "rev-parse", "--short", "HEAD")
        } else {
            commandLine("git", "rev-parse", "HEAD")
        }
    }
    return execProvider.standardOutput.asText.map { it.trim() }
}
