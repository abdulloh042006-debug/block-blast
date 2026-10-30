plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ultimategames.gridbloom"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ultimategames.gridbloom"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isDebuggable = true
            isMinifyEnabled = false

            // Official Google AdMob Test IDs for Debug builds (Zero live ad traffic)
            // Ref: https://developers.google.com/admob/android/test-ads
            manifestPlaceholders["ADMOB_APP_ID"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("Boolean", "IS_TEST_AD_MODE", "true")
            buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-3940256099942544~3347511713\"")
            buildConfigField("String", "ADMOB_REWARDED_UNIT_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "PRIVACY_POLICY_URL", "\"https://example.com/privacy-policy\"")
        }

        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Owner's verified production AdMob IDs read from gradle.properties or environment variables.
            // ReleaseConfigValidator enforces that placeholder/test IDs are rejected before serving live traffic.
            val prodAppId = (project.findProperty("PROD_ADMOB_APP_ID") as String?)
                ?: System.getenv("PROD_ADMOB_APP_ID")
                ?: "REPLACE_WITH_PRODUCTION_ADMOB_APP_ID"
            val prodRewardedId = (project.findProperty("PROD_ADMOB_REWARDED_ID") as String?)
                ?: System.getenv("PROD_ADMOB_REWARDED_ID")
                ?: "REPLACE_WITH_PRODUCTION_REWARDED_UNIT_ID"
            val prodInterstitialId = (project.findProperty("PROD_ADMOB_INTERSTITIAL_ID") as String?)
                ?: System.getenv("PROD_ADMOB_INTERSTITIAL_ID")
                ?: "REPLACE_WITH_PRODUCTION_INTERSTITIAL_UNIT_ID"
            val prodBannerId = (project.findProperty("PROD_ADMOB_BANNER_ID") as String?)
                ?: System.getenv("PROD_ADMOB_BANNER_ID")
                ?: "REPLACE_WITH_PRODUCTION_BANNER_UNIT_ID"
            val privacyUrl = (project.findProperty("PROD_PRIVACY_POLICY_URL") as String?)
                ?: System.getenv("PROD_PRIVACY_POLICY_URL")
                ?: "https://yourdomain.com/privacy-policy"

            manifestPlaceholders["ADMOB_APP_ID"] = prodAppId
            buildConfigField("Boolean", "IS_TEST_AD_MODE", "false")
            buildConfigField("String", "ADMOB_APP_ID", "\"$prodAppId\"")
            buildConfigField("String", "ADMOB_REWARDED_UNIT_ID", "\"$prodRewardedId\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", "\"$prodInterstitialId\"")
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$prodBannerId\"")
            buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // WebViewAssetLoader: serves the bundled game from the app itself over a stable https origin
    implementation("androidx.webkit:webkit:1.11.0")

    // Official Google Mobile Ads SDK & User Messaging Platform (UMP) SDK
    // Ref: https://developers.google.com/admob/android/quick-start
    // Ref: https://developers.google.com/admob/android/privacy
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
