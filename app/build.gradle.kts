// AGP 9 自带 Kotlin 支持，但默认绑定的 Kotlin 2.2；LiteRT-LM 0.19 需要 2.3 以上，这里把编译器升到 2.4。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
