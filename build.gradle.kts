import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.wrapper.Wrapper
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    kotlin("jvm") version "2.4.10"
}

group = "cc.kites"
version = "1.1.0"
description = "Workspace-first AI Agent runtime for Paper and Folia"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        javaParameters.set(true)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.graalvm.polyglot:polyglot:25.2.4")
    runtimeOnly("org.graalvm.polyglot:js:25.2.4")
    implementation("org.yaml:snakeyaml:2.4")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.4")
    testCompileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        events("failed", "skipped")
    }
}

tasks.jar {
    archiveBaseName.set("Mineclaw")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.filter { dependency -> dependency.extension == "jar" }.map(::zipTree)
    })
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-APACHE-2.0.txt" }
    }
    from("NOTICE") {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    manifest.attributes(
        "Implementation-Title" to "Mineclaw",
        "Implementation-Version" to project.version,
        "Multi-Release" to "true",
    )
    exclude(
        ".env",
        ".env.*",
        "**/.env",
        "**/.env.*",
        "module-info.class",
        "META-INF/versions/**/module-info.class",
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
    )
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<Jar>("sourcesJar") {
    archiveBaseName.set("Mineclaw")
    exclude(".env", ".env.*", "**/.env", "**/.env.*")
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-APACHE-2.0.txt" }
    }
    from("NOTICE") {
        into("META-INF")
        rename { "NOTICE.txt" }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.register<Sync>("assemblePlugin") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(layout.buildDirectory.dir("plugins"))
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "9.5.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
    validateDistributionUrl = false
}
