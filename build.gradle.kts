import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    application
}

val ktorVersion = "2.3.11"
val graphqlJavaVersion = "21.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("com.graphql-java:graphql-java:$graphqlJavaVersion")
    implementation("com.graphql-java:graphql-java-extended-scalars:21.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.gateway.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
