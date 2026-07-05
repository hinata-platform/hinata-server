import org.apache.tools.ant.filters.ReplaceTokens
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "com.ahmadre"
version = "1.0.0"
description = "Hinata - open source, self-hosted project management server"

// --- Pinned versions for dependencies not governed by the Spring Boot BOM ---
val bucket4jVersion = "8.10.1"
val minioVersion = "8.6.0"
val openpdfVersion = "2.0.3"
val springdocScalarVersion = "3.0.3"
// Override the Bouncy Castle version pulled in transitively by
// spring-security-saml2 / OpenSAML. The BOM pins 1.78.1, which is affected by a
// covert timing channel (GHSA-p93r-85wp-75v3) and an LDAP injection
// (GHSA-c3fc-8qff-9hwx); 1.84 fixes both.
val bouncyCastleVersion = "1.84"
// MinIO 8.6.0 declares OkHttp 5.x, whose Maven artifact is a Kotlin-multiplatform
// aggregator (classes live in okhttp-jvm), so okhttp3.HttpUrl is missing from the
// compile classpath. Pin the well-behaved single-jar 4.12.0 release, which exposes
// the same stable OkHttp API MinIO uses.
val okhttpVersion = "4.12.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // OpenSAML 5 (required by spring-boot-starter-security-saml2) is not on Maven
    // Central; it is published to the Shibboleth releases repository.
    maven {
        name = "shibboleth-releases"
        url = uri("https://build.shibboleth.net/maven/releases/")
        mavenContent { releasesOnly() }
    }
}

dependencies {
    // Import the Spring Boot dependency BOM (equivalent to spring-boot-starter-parent).
    // The annotation-processor configurations are resolved independently and do not
    // extend `implementation`, so the BOM must be applied to them too — otherwise the
    // BOM-managed Lombok version cannot be resolved.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
    testAnnotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))

    // Web / data / platform starters
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // HTML e-mail templating (transactional account & notification mails)
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // PDF generation: GDPR (Art. 15) self-service data report
    implementation("com.github.librepdf:openpdf:$openpdfVersion")

    // Security & SSO
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security-saml2")
    implementation("org.springframework.boot:spring-boot-starter-ldap")
    implementation("org.springframework.security:spring-security-ldap")

    // Rate limiting
    implementation("com.bucket4j:bucket4j-core:$bucket4jVersion")

    // S3 object storage (MinIO or any S3-compatible)
    implementation("io.minio:minio:$minioVersion")

    // API documentation: OpenAPI 3.1 spec + Scalar UI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-scalar:$springdocScalarVersion")

    // Lombok (compile-time only — excluded from the runtime/boot jar automatically)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-mongodb-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-mail-test")
    // Real-infrastructure integration tests: a throwaway MongoDB container wired
    // in via @ServiceConnection (skipped automatically when Docker is unavailable).
    // Spring Boot manages Testcontainers through a nested BOM import that Gradle's
    // platform does not propagate to individual modules, so import it explicitly
    // at the exact version Spring Boot 4.1.0 pins (testcontainers.version=2.0.5).
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // --- Forced versions (override the transitive graph exactly like Maven's
    //     <dependencyManagement>). `strictly` is required to force a downgrade. ---
    constraints {
        implementation("com.squareup.okhttp3:okhttp") {
            version { strictly(okhttpVersion) }
            because("MinIO pulls the KMP aggregator OkHttp 5.x; pin the single-jar 4.12.0")
        }
        listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach { artifact ->
            implementation("org.bouncycastle:$artifact") {
                version { strictly(bouncyCastleVersion) }
                because("BOM pins vulnerable BC 1.78.1 (GHSA-p93r-85wp-75v3, GHSA-c3fc-8qff-9hwx); 1.84 fixes both")
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Retain method parameter names — required by Spring MVC / data binding.
    // The Spring Boot plugin adds this too; keep it explicit to be safe.
    options.compilerArgs.add("-parameters")
}

// Replicate Maven's resource filtering of @project.version@ in application*.yml.
// ReplaceTokens uses @token@ delimiters, so Spring's ${...} placeholders are left
// untouched (unlike Gradle's expand(), which would try to evaluate them).
tasks.named<ProcessResources>("processResources") {
    val tokens = mapOf("project.version" to version.toString())
    filesMatching(listOf("application.yml", "application-*.yml")) {
        filter<ReplaceTokens>("tokens" to tokens)
    }
}

// Build only the executable boot jar (no plain -plain.jar), so the Dockerfile
// COPY glob resolves to a single artifact.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
