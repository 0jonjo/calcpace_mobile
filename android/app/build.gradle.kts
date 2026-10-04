import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
            applicationIdSuffix = ".debug"
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("dev.hotwire:core:1.3.1")
    implementation("dev.hotwire:navigation-fragments:1.3.1")

    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.browser:browser:1.10.0")

    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
