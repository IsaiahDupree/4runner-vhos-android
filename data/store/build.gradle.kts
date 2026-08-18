plugins {
    id("com.android.library")
}

android {
    namespace = "dev.vhos.store"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:digitaltwin"))
    implementation(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:sync"))
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation("junit:junit:4.13.2")
}
