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

    testImplementation(kotlin("test"))
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
