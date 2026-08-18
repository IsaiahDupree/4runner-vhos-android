plugins {
    id("com.android.library")
}

android {
    namespace = "dev.vhos.discovery"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:protocol"))
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation("junit:junit:4.13.2")
}
