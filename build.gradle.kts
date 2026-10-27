plugins {
    kotlin("jvm") version "1.9.23"
    `java`
    application
}

group = "io.github.your-username.triage4s-quickstart"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("io.github.pavanvamsi3.triage4s.KtMain")
}

repositories {
    mavenCentral()
}

dependencies {
    // The actual published llm4s-core for Scala 3
    implementation("org.llm4s:llm4s-core_3:0.5.1")

    // Kotlin stdlib
    implementation(kotlin("stdlib-jdk8"))

    // Optional: upickle JSON handling (same version used by triage4s)
    implementation("com.lihaoyi::upickle:4.4.3")
}
