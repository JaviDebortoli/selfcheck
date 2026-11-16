// Declarative medical rules and their Spanish/English texts (spec section 4.4), plus the Kotlin model that loads them.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

val bootBom = dependencies.platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")

dependencies {
    api(project(":contracts"))
    implementation(bootBom)
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(bootBom)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
