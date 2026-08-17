plugins {
    id("com.android.library")
}

android {
    namespace = "dev.vhos.release"
    compileSdk = 37

    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("junit:junit:4.13.2")
}
