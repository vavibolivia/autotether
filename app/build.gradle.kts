plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.across.autotether"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.across.autotether"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("autotether.hostIp", System.getProperty("autotether.hostIp") ?: "")
            it.testLogging.showStandardStreams = true
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}

// 배포 패키지: dist/AutoTether-Setup/ (폴더) + dist/AutoTether-Setup.zip
val distDir = rootProject.layout.projectDirectory.dir("dist")
val distFolder = tasks.register<Sync>("distFolder") {
    dependsOn("assembleDebug")
    into(distDir.dir("AutoTether-Setup"))
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")) { rename { "AutoTether.apk" } }
    from(rootProject.file("setup")) { exclude("setup.log", "platform-tools/**") }
}
tasks.register<Zip>("dist") {
    dependsOn(distFolder)
    metadataCharset = "UTF-8"
    archiveFileName.set("AutoTether-Setup.zip")
    destinationDirectory.set(distDir)
    from(distDir.dir("AutoTether-Setup")) { into("AutoTether-Setup") }
}
