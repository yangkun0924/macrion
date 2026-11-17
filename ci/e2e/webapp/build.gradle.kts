// Small app used only by the end to end test: a native screen with a button that opens a web page menu, like the
// company apps that show their order page in a WebView.
plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.e2e.coffee"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.e2e.coffee"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
}
