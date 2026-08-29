import java.util.Properties

plugins {
    id("com.android.application")
}

base {
    archivesName.set("BiliFix")
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propertyName: String, environmentName: String): String? =
    System.getenv(environmentName)?.takeIf { it.isNotBlank() }
        ?: releaseSigningProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = signingValue("storeFile", "BILIFIX_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "BILIFIX_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "BILIFIX_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "BILIFIX_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val hasAnyReleaseSigningValue = releaseSigningValues.any { it != null }
val hasCompleteReleaseSigning = releaseSigningValues.all { it != null }

check(!hasAnyReleaseSigningValue || hasCompleteReleaseSigning) {
    "Release signing is incomplete. Configure all four values in keystore.properties " +
        "or BILIFIX_KEYSTORE_FILE/BILIFIX_STORE_PASSWORD/BILIFIX_KEY_ALIAS/" +
        "BILIFIX_KEY_PASSWORD."
}

val releaseStoreFile = releaseStoreFilePath?.let(rootProject::file)
if (hasCompleteReleaseSigning) {
    check(releaseStoreFile?.isFile == true) {
        "Release keystore does not exist: ${releaseStoreFile?.absolutePath}"
    }
}

val libxposedApiVersion = providers.gradleProperty("libxposedApiVersion")
    .orElse("102.0.0")

android {
    namespace = "com.xjw.bilifix.in"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.xjw.bilifix.in"
        minSdk = 29
        targetSdk = 36
        versionCode = 62631
        versionName = "0.9.33"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/*.version"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:${libxposedApiVersion.get()}") {
        isTransitive = false
    }
    implementation("org.luckypray:dexkit:2.2.0")
}

configurations.configureEach {
    if (name.endsWith("RuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains", module = "annotations")
    }
}
