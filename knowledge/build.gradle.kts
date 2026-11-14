// Declarative medical rules and their Spanish/English texts (spec section 4.4), packaged as classpath resources.
plugins {
    `java-library`
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}
