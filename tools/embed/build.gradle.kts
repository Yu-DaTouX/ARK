plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(files("litertlm-jvm.jar"))
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("EmbedKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g", "--enable-native-access=ALL-UNNAMED")
}
