import java.util.Properties
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

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

// 同一份源码支持完整版和精简版；精简版不配置 CMake，也不读取地图或头像模型。
val includePathing = providers.gradleProperty("includePathing").orElse("true").get().let {
    require(it == "true" || it == "false") { "includePathing 必须为 true 或 false" }
    it.toBoolean()
}
val liteAssetsDir = layout.buildDirectory.dir("generated/liteAssets")
val prepareLiteAssets by tasks.registering(Sync::class) {
    from("src/main/assets")
    exclude("pathing/**", "recognition/Pathing/**", "recognition/Party/**", "licenses/ncnn.txt")
    into(liteAssetsDir)
}

// 从本次打包的路线提取匹配方式，选择界面无需逐条解析数千个路线文件。
val routeMetadataDir = layout.buildDirectory.dir("generated/routeMetadata/assets")
val generateRouteMetadata by tasks.registering {
    val routes = fileTree("src/main/assets/pathing/routes") { include("**/*.json") }
    inputs.files(routes).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(routeMetadataDir)
    doLast {
        val root = file("src/main/assets/pathing/routes").toPath()
        val metadata = routes.files.sortedBy { it.path }.associate { route ->
            val path = root.relativize(route.toPath()).toString().replace('\\', '/')
            // 无效路线仍交给执行器记录并跳过；列表明确显示读取失败，避免误报算法。
            val info = runCatching {
                val parsed = JsonSlurper().parseText(route.readText(Charsets.UTF_8)) as Map<*, *>
                val method = (parsed["info"] as? Map<*, *>)?.get("map_match_method") as? String
                mapOf("map_match_method" to method?.takeIf { it.isNotBlank() })
            }.getOrNull()
            path to info
        }
        val output = routeMetadataDir.get().file("pathing/routes-metadata.json").asFile
        output.parentFile.mkdirs()
        output.writeText(JsonOutput.toJson(metadata), Charsets.UTF_8)
    }
}

android {
    namespace = "com.bettergi.pocket"
    sourceSets.getByName("main").assets.apply {
        if (includePathing) {
            srcDir(routeMetadataDir.get().asFile)
        } else {
            setSrcDirs(listOf(liteAssetsDir.get().asFile))
        }
    }
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
        buildConfigField("boolean", "PATHING_ENABLED", includePathing.toString())
        ndk { abiFilters += "arm64-v8a" }

        // AppKey 为空时不启动统计，可通过 local.properties 或 -P 参数配置。
        resValue("string", "baidu_mtj_app_key", analyticsProperty("BAIDU_MTJ_APP_KEY"))
        resValue("string", "baidu_mtj_channel", analyticsProperty("BAIDU_MTJ_CHANNEL", "official"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ndkVersion = "28.2.13676358"
    if (includePathing) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
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
        buildConfig = true
    }

    androidResources {
        // 地图追踪资源（assets/pathing，约 185 MiB）通过 AssetManager.openFd 直接 mmap 解码，必须不压缩
        noCompress += listOf("webp", "png", "bin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(if (includePathing) generateRouteMetadata else prepareLiteAssets)
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
