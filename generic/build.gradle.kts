plugins {
    alias(libs.plugins.android.application)
}

// Explicit, local-only packaging input. Source/CI builds do not contain an MFi identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay.generic"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.shihab.diplay.generic"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-generic"
        missingDimensionStrategy("vendor", "generic")
    }
    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":shared"))
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}

val credentialPatterns = listOf("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
    "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
val authenticationNames = listOf("identity.pk8", "certificate.p7b")
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) { credentialPatterns.forEach { include(it) } }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject credential containers outside the explicit local authentication input."
    inputs.files(credentialAssets)
    doLast {
        val allowed = localAuthenticationAssets?.let { directory ->
            authenticationNames.map { directory.resolve("offline-mfi/$it").canonicalFile }.toSet()
        }.orEmpty()
        check(allowed.all { it.isFile && it.length() in 1L..16384L }) {
            "Explicit local authentication files must both be present and 1–16384 bytes"
        }
        check(credentialAssets.files.all { it.canonicalFile in allowed }) {
            "Unexpected credential files in APK assets"
        }
        localAuthenticationAssets?.let { directory ->
            check(directory.walkTopDown().filter { it.isFile }.all { it.canonicalFile in allowed }) {
                "The explicit authentication asset directory must contain only the intended two files"
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Inspect merged assets too: a dependency must not silently package another identity.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    doLast {
        val credentials = outputs.files.asFileTree.matching {
            credentialPatterns.forEach { include(it) }
        }.files
        check(credentials.all { entry ->
            localAuthenticationAssets != null && authenticationNames.any { name ->
                entry.invariantSeparatorsPath.endsWith("/offline-mfi/$name") &&
                    entry.readBytes().contentEquals(localAuthenticationAssets.resolve("offline-mfi/$name").readBytes())
            }
        }) { "Unexpected credential files in merged APK assets" }
    }
}

val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require explicit runtime authentication for a standalone generic test APK."
    doLast {
        val directory = checkNotNull(localAuthenticationAssets) {
            "Standalone builds require DIPLAY_AUTH_ASSETS_DIR; ordinary builds are source-only."
        }
        check(authenticationNames.all {
            directory.resolve("offline-mfi/$it").let { file ->
                file.isFile && file.length() in 1L..16384L
            }
        }) { "Standalone authentication files are missing, empty or oversized" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build the generic test APK using explicit local authentication assets."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
