import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing lives outside the repo. Point CALCPACE_SIGNING at a
// properties file with storeFile, storePassword, keyAlias and keyPassword
// (by default ~/.calcpace/signing.properties). Without it, release builds
// come out unsigned and debug builds are unaffected.
val signingProperties = Properties().apply {
    val path = System.getenv("CALCPACE_SIGNING")
        ?: "${System.getProperty("user.home")}/.calcpace/signing.properties"
    val file = File(path)
    if (file.exists()) file.inputStream().use { load(it) }
}

// Firebase options for push notifications, read straight from
// google-services.json, which stays out of the repo (the repo is public).
// By default ~/.config/calcpace/google-services.json; point
// -Pcalcpace.googleServices=/path/to/google-services.json elsewhere. No file
// means empty options and an app that runs with push off, which is what CI
// builds. A release build refuses to go out like that (checkFirebaseConfig).
// The google-services plugin is not used: it insists on the file sitting
// inside the module.
val firebasePackage = "app.calcpace.twa"
// A relative -Pcalcpace.googleServices resolves against android/.
val googleServicesFile = rootProject.file(
    providers.gradleProperty("calcpace.googleServices").orNull
        ?: "${System.getProperty("user.home")}/.config/calcpace/google-services.json"
)

@Suppress("UNCHECKED_CAST")
val firebase: Map<String, String> = if (googleServicesFile.isFile) {
    runCatching {
        val json = JsonSlurper().parse(googleServicesFile) as Map<String, Any?>
        val project = json["project_info"] as Map<String, Any?>
        val client = (json["client"] as List<Map<String, Any?>>).firstOrNull {
            val info = it["client_info"] as Map<String, Any?>
            (info["android_client_info"] as Map<String, Any?>)["package_name"] == firebasePackage
        } ?: throw GradleException("no Android client for $firebasePackage")
        val apiKey = (client["api_key"] as List<Map<String, Any?>>).firstOrNull()
            ?: throw GradleException("no api_key for $firebasePackage")
        mapOf(
            "PROJECT_ID" to project["project_id"] as String,
            "SENDER_ID" to project["project_number"] as String,
            "APP_ID" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String,
            "API_KEY" to apiKey["current_key"] as String,
        )
    }.getOrElse { throw GradleException("Can't read Firebase options from $googleServicesFile: ${it.message}") }
} else {
    emptyMap()
}

android {
    namespace = "app.calcpace"
    compileSdk = 36

    defaultConfig {
        // Kept from the Trusted Web Activity this app replaces: the Play
        // listing, its installs and its reviews are tied to this id.
        applicationId = "app.calcpace.twa"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "2.0.0"

        // The site the app wraps. Override for a local server with
        // ./gradlew installDebug -Pcalcpace.baseUrl=http://192.168.0.10:3001
        val baseUrl = (project.findProperty("calcpace.baseUrl") as String?) ?: "https://calcpace.app"
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")

        listOf("PROJECT_ID", "SENDER_ID", "APP_ID", "API_KEY").forEach { key ->
            buildConfigField("String", "FIREBASE_$key", "\"${firebase[key].orEmpty()}\"")
        }
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            // ".debug" lets it sit next to the Play build. Pass
            // -Pcalcpace.debugIdSuffix= (empty) to test what only the real
            // package receives, like the sign-in page's "Open Calcpace" intent.
            applicationIdSuffix = (project.findProperty("calcpace.debugIdSuffix") as String?) ?: ".debug"
            // Lets a local server's links (e.g. the sign-in page's
            // "Open Calcpace" intent) reach a debug build, as calcpace.app's
            // App Links reach the release one.
            val debugSite = URI(
                (project.findProperty("calcpace.baseUrl") as String?) ?: "https://calcpace.app"
            )
            manifestPlaceholders["debugSiteScheme"] = debugSite.scheme
            manifestPlaceholders["debugSiteHost"] = debugSite.host
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("main") { java { srcDirs("src/main/kotlin") } }
        named("test") { java { srcDirs("src/test/kotlin") } }
        // Debug-only tools (HcSeedActivity): never in a release build.
        named("debug") { java { srcDirs("src/debug/kotlin") } }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// A release without push would ship silently broken notifications.
val firebaseConfigured = firebase.isNotEmpty()
val firebaseFilePath = googleServicesFile.path
val checkFirebaseConfig by tasks.registering {
    description = "Fails unless the Firebase options for push were found."
    inputs.property("configured", firebaseConfigured)
    doLast {
        if (!firebaseConfigured) {
            throw GradleException(
                "No Firebase config at $firebaseFilePath: a release build needs it for push. " +
                    "Put google-services.json there or pass -Pcalcpace.googleServices=/path/to/it."
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkFirebaseConfig) }

dependencies {
    implementation("dev.hotwire:core:1.3.1")
    implementation("dev.hotwire:navigation-fragments:1.3.1")

    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.browser:browser:1.10.0")

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    // Hotwire's KotlinXJsonConverter encodes bridge replies; 1.10.0 is the
    // line built against Kotlin 2.3.0.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Health Connect import: read on the phone, sent by a background worker.
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Hotwire 1.3.1 already ships OkHttp 5.3.2 at runtime only; declared to
    // compile against it, at the same version, so there is one OkHttp.
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
