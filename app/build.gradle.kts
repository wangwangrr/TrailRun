import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 用系统自带的 debug keystore 给 release 也签名。
//
// 理由不是图省事，而是**升级路径**：Android 只允许同签名的包互相覆盖安装。
// 现在手机上装的是 debug 版，如果 release 换另一套 keystore，
// 用户必须先卸载 —— 已保存的路线预设会跟着丢掉。
// 两个 buildType 共用同一份签名，就能直接覆盖升级。
//
// 代价说清楚：debug keystore 的密码是公开的（android / androiddebugkey），
// 任何人都能拿它伪造一个「同签名」的包。这里可以接受 ——
// 本工程是自用与学习用，也不上架任何应用商店。
// 真要正式分发，请自建 keystore 并换掉下面那四行。
//
// ⚠️ 必须定义在 android {} **外面**：块内的 `java` 是 AndroidExtension 的属性，
// 写 `java.io.File` 会被解析成 `java`(属性).io，报 Unresolved reference: io。
val sharedKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

android {
    namespace = "com.trailrun.mockgps"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trailrun.mockgps"
        minSdk = 26
        targetSdk = 34
        // 8 / 1.2.4：对照影梭（ZCShou/GoGoGo）源码逐项比对后，补上三处关键差异 ——
        // 1) Manifest 缺 ACCESS_BACKGROUND_LOCATION：切到其他 App 后本应用转入后台，
        //    位置推送被系统掐断，对方读到真实定位，极易被误判成「对方有反作弊检测」；
        // 2) 推送频率 200ms → 100ms（影梭用 Thread.sleep(100)，即 10 Hz）；
        // 3) 位置加 extras satellites=7（影梭的做法，部分定位 SDK 会读它判断是否真实 GPS）。
        // versionCode 必须递增，否则新包盖不上手机上已装的旧包。
        versionCode = 12
        versionName = "1.2.8"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (sharedKeystore.exists()) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        // debug 版原样保留：不裁剪、可调试，出问题时用它回退。
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 代码裁剪 + 资源裁剪。体积大头是 material-icons-extended
            // （85 MB 的 jar，几千个图标），debug 版会把它们全部打进包里；
            // 开启裁剪后只有真正引用的那十几个图标会留下。
            isMinifyEnabled = true
            isShrinkResources = true
            if (sharedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("shared")
            }
            proguardFiles(
                // 用 proguard-android.txt，**不是** -optimize 那份。
                //
                // 第一版用的 -optimize，构建出来的包真机实测**会闪退**。
                // 首要可疑点是 R8 的优化阶段：方法内联会把 @Composable 函数、
                // Kotlin object 的方法直接内联进调用点，类合并会把小类并进大类，
                // 而 Compose 运行时对编译器生成的调用结构是有约定的。
                // 换成不带 optimize 的这份，R8 只做裁剪（移除未使用的类 / 方法 / 字段），
                // 体积会比激进版大一些，换回「能跑」。
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // 本应用**就是**模拟位置工具：ACCESS_MOCK_LOCATION 是它的核心权限，
        // 不是「不小心把测试权限带进了正式包」。
        // 这条 lint 规则假定的是普通应用，在这里必须关掉 ——
        // 否则 release 构建会被 lintVitalRelease 直接拦下，一个包都出不来。
        disable += "MockLocation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // 离线地图（OpenStreetMap 瓦片，无需 API Key）
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    // osmdroid 的 Configuration.load(Context, SharedPreferences) 需要它
    implementation("androidx.preference:preference-ktx:1.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
