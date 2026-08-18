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
    api("androidx.sqlite:sqlite:2.7.0")
    implementation("com.google.code.gson:gson:2.14.0")
    api("net.zetetic:sqlcipher-android:4.18.0@aar")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
