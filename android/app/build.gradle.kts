plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.vaaniledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.vaaniledger"
        // 26 is the floor: TYPE_APPLICATION_OVERLAY and notification channels
        // both arrive in Oreo, and the overlay is the whole product.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Physical devices cannot reach the host's localhost. Override per
        // build with -PapiBase=http://<your-lan-ip>:8000/
        val apiBase = (project.findProperty("apiBase") as String?)
            ?: "http://10.0.2.2:8000/"   // the emulator's alias for the host
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
    }

    buildTypes {
        debug {
            // The API runs over plain HTTP in dev; cleartext is allowed only
            // in debug so a release build cannot ship it.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Needed for java.time on API 26 without desugaring surprises.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    // Emits the schema JSON so migrations can be written against a real diff
    // rather than from memory. This database holds someone's money.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val room = "2.6.1"
    val lifecycle = "2.8.7"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")

    // Room
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // Sync
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ML Kit OCR lands with ScreenCaptureService in week 3. Left out until
    // there is code using it — an unused 20MB dependency slows every build.

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:$room")
}
