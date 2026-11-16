plugins {
    `java-library`
    alias(libs.plugins.avro)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

avro {
    stringType.set("String")
    fieldVisibility.set("PRIVATE")
}

dependencies {
    api(libs.avro)
}
