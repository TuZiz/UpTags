plugins {
    kotlin("jvm") version "2.1.21"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "cn.aing"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    mavenCentral()
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    implementation("com.mysql:mysql-connector-j:8.4.0")
    implementation("redis.clients:jedis:5.2.0")

    testImplementation(kotlin("test"))
    testImplementation("org.spigotmc:spigot-api:1.20.6-R0.1-SNAPSHOT")
    testImplementation("me.clip:placeholderapi:2.11.6")
    testImplementation("io.mockk:mockk:1.13.12")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
    exclude("lang/**")
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.version))
    }
}

tasks.jar {
    archiveClassifier.set("thin")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
