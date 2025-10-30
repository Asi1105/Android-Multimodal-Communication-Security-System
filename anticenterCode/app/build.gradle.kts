import java.util.Properties
import java.io.FileInputStream
import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("jacoco")
}

// // Load local.properties
// val localProperties = Properties()
// val localPropertiesFile = rootProject.file("local.properties")
// if (localPropertiesFile.exists()) {
//     localProperties.load(FileInputStream(localPropertiesFile))
// }

// // Read API keys from local.properties first, then gradle.properties, then use default
// val rdApiKey = localProperties.getProperty("RD_API_KEY") 
//     ?: project.findProperty("RD_API_KEY") as String? 
//     ?: "rd_40e31fce774ee201_05ad443c2d4f59b8c50c970c2b3ad454"
    
// val difyApiKey = localProperties.getProperty("DIFY_API_KEY") 
//     ?: project.findProperty("DIFY_API_KEY") as String? 
//     ?: "app-a7LurHDnzB3kQWAEHuq3z9YT"

// android {
//     namespace = "com.example.anticenter"
//     compileSdk = 36

//     defaultConfig {
//         applicationId = "com.example.anticenter"
//         minSdk = 32
//         targetSdk = 36
//         versionCode = 1
//         versionName = "1.0"

//         testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

//         buildConfigField("String", "RD_API_KEY", "\"$rdApiKey\"")
//         buildConfigField("String", "DIFY_API_KEY", "\"$difyApiKey\"")

//     }

//     buildTypes {
//         release {
//             isMinifyEnabled = false
//             proguardFiles(
//                 getDefaultProguardFile("proguard-android-optimize.txt"),
//                 "proguard-rules.pro"
//             )
//         }
//     }
//     compileOptions {
//         sourceCompatibility = JavaVersion.VERSION_11
//         targetCompatibility = JavaVersion.VERSION_11
//     }
//     kotlinOptions {
//         jvmTarget = "11"
//     }
//     buildFeatures {
//         compose = true
//         buildConfig = true
//     }
//     packaging {
//         resources {
//             excludes += "/META-INF/INDEX.LIST"
//             excludes += "/META-INF/DEPENDENCIES"

//             excludes += "/META-INF/LICENSE"
//             excludes += "/META-INF/LICENSE.txt"
//             excludes += "/META-INF/NOTICE"
//             excludes += "/META-INF/NOTICE.txt"
//             excludes += "/META-INF/ASL2.0"
//             excludes += "/META-INF/*.kotlin_module"
//         }
//     }
// }
// 读取 local.properties 里的 RD_API_KEY（不要加引号）
val rdApiKey: String = gradleLocalProperties(rootDir, providers)
    .getProperty("RD_API_KEY") ?: ""

// 读取 local.properties 里的 DIFY_API_KEY
val difyApiKey: String = gradleLocalProperties(rootDir, providers)
    .getProperty("DIFY_API_KEY") ?: ""

// 读取 local.properties 里的 DIFY_USER_EMAIL
val difyUserEmail: String = gradleLocalProperties(rootDir, providers)
    .getProperty("DIFY_USER_EMAIL") ?: ""

//（可选）缺失就直接中断编译，避免生成空字符串
if (rdApiKey.isBlank()) {
    throw GradleException("RD_API_KEY is missing. Put RD_API_KEY=rd_xxx in local.properties")
}

if (difyApiKey.isBlank()) {
    throw GradleException("DIFY_API_KEY is missing. Put DIFY_API_KEY=app-xxx in local.properties")
}

if (difyUserEmail.isBlank()) {
    throw GradleException("DIFY_USER_EMAIL is missing. Put DIFY_USER_EMAIL=your@email.com in local.properties")
}

android {
    namespace = "com.example.anticenter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.anticenter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "RD_API_KEY", "\"$rdApiKey\"")
        buildConfigField("String", "DIFY_API_KEY", "\"$difyApiKey\"")
        buildConfigField("String", "DIFY_USER_EMAIL", "\"$difyUserEmail\"")
        manifestPlaceholders["RD_API_KEY"] = rdApiKey
        manifestPlaceholders["DIFY_API_KEY"] = difyApiKey

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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    // 测试覆盖率配置
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            
            // 为 Robolectric 配置
            all {
                it.systemProperty("robolectric.logging.enabled", "true")
                it.jvmArgs("-Xmx2048m", "-XX:MaxMetaspaceSize=512m")
            }
        }
    }
    
    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST"
            )
        }
    }
}
dependencies {
    val room_version = "2.7.2"

    implementation("androidx.room:room-runtime:$room_version")

    // If this project uses any Kotlin source, use Kotlin Symbol Processing (KSP)
    // See Add the KSP plugin to your project
    ksp("androidx.room:room-compiler:$room_version")

    // If this project only uses Java source, use the Java annotationProcessor
    // No additional plugins are necessary
    annotationProcessor("androidx.room:room-compiler:$room_version")

    // optional - Kotlin Extensions and Coroutines support for Room
    implementation("androidx.room:room-ktx:$room_version")
    
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.ui)
    implementation("androidx.cardview:cardview:1.0.0")
    
    // ========== Testing Dependencies ==========
    // Unit Testing - Core
    testImplementation(libs.junit)
    testImplementation("junit:junit:4.13.2")
    
    // Mockito for mocking
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    
    // Truth for assertions
    testImplementation("com.google.truth:truth:1.1.5")
    
    // Coroutines testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    // Flow testing
    testImplementation("app.cash.turbine:turbine:1.0.0")
    
    // AndroidX testing
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    
    // Robolectric for Android unit tests (4.14.1+ 支持JDK 21)
    testImplementation("org.robolectric:robolectric:4.14.1")
    
    // For Kotlin reflection
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:1.9.20")
    
    // Instrumented Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("org.mockito:mockito-android:5.7.0")
    androidTestImplementation("com.google.truth:truth:1.1.5")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    // Compose Testing
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // optional - RxJava2 support
    implementation("androidx.datastore:datastore-preferences-rxjava2:1.1.7")

    // optional - RxJava3 support
    implementation("androidx.datastore:datastore-preferences-rxjava3:1.1.7")

    // Gmail API and Google Sign-In
    implementation("com.google.api-client:google-api-client-android:2.8.1")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.39.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20250630-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:1.47.1")
    implementation("com.google.android.gms:play-services-auth:21.0.0")

    // Reality Defender
    implementation(files("libs/realitydefender-sdk-0.1.0.jar"))
    implementation("com.fasterxml.jackson.core:jackson-core:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.16.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    // OpenCSV for CSV file export functionality
    implementation("com.opencsv:opencsv:5.9")

    // FFmpegKit - 手动添加 AAR 文件（从 GitHub Releases 下载）
    // implementation("com.arthenica:ffmpeg-kit-full:6.0-2")  // Maven Central 上不存在
    // 暂时注释，需要手动下载 AAR 文件放到 app/libs/ 目录
}

// 强制测试任务使用 JDK 17（解决 Robolectric 在 JDK 21 上的 VerifyError）
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

jacoco {
    toolVersion = "0.8.10"
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report for debug unit tests"
    dependsOn("testDebugUnitTest")

    executionData.setFrom(fileTree(buildDir).include("**/*.exec"))

    val coverageIncludes = listOf(
        "com/example/anticenter/data/**",
        "com/example/anticenter/database/**",
        "com/example/anticenter/services/CoreProtectionService*",
        "com/example/anticenter/services/PhishingDataConverter*",
        "com/example/anticenter/services/AntiCenterNotificationListener*",
        "com/example/anticenter/services/OverlayBannerService*"
    )
    val kotlinDebugTree = fileTree("$buildDir/tmp/kotlin-classes/debug") {
        include(coverageIncludes)
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/test/**")
    }
    val javaDebugTree = fileTree("$buildDir/intermediates/javac/debug/classes") {
        include(coverageIncludes)
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/test/**")
    }
    classDirectories.setFrom(files(kotlinDebugTree, javaDebugTree))
    sourceDirectories.setFrom(
        files(
            "src/main/java/com/example/anticenter/data",
            "src/main/java/com/example/anticenter/database",
            "src/main/java/com/example/anticenter/services",
            "src/main/kotlin/com/example/anticenter/data",
            "src/main/kotlin/com/example/anticenter/database",
            "src/main/kotlin/com/example/anticenter/services"
        )
    )

    reports {
        html.required.set(true)
        html.outputLocation.set(file("$buildDir/reports/jacoco"))
        xml.required.set(true)
        csv.required.set(false)
    }
}