import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localConfig = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.reader(Charsets.UTF_8).use { load(it) }
}

fun analyticsProperty(name: String, fallback: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: localConfig.getProperty(name)
        ?: fallback

android {
    namespace = "com.bettergi.pocket"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bettergi.pocket"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // AppKey 为空时不启动统计，可通过 local.properties 或 -P 参数配置。
        resValue("string", "baidu_mtj_app_key", analyticsProperty("BAIDU_MTJ_APP_KEY"))
        resValue("string", "baidu_mtj_channel", analyticsProperty("BAIDU_MTJ_CHANNEL", "official"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        resValues = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 官网新版 SDK；Maven Central 的 mtj-sdk 仍是旧版，来源与校验值见 libs/README.md。
    implementation(files("libs/Baidu_Mtj_android_4.0.11.1.jar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.opencv)
    implementation(libs.mlkit.text.recognition.chinese)
    testImplementation(libs.junit)
    val desktopOpenCv = file("libs/opencv-4.9.0-0.jar")
    if (desktopOpenCv.exists()) {
        testImplementation(files(desktopOpenCv))
    } else {
        testImplementation(libs.opencv.desktop)
    }
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

configurations.matching {
    val n = name.lowercase()
    n.contains("test") && n.contains("classpath") && !n.contains("androidtest")
}.configureEach {
    exclude(group = "org.opencv", module = "opencv")
}
