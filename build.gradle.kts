plugins {
    application
    java
}

repositories {
    mavenCentral()
}

dependencies {
    // CLI framework
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")

    // HTTP & JSON for JMAP
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Google Workspace Gmail API
    implementation("com.google.api-client:google-api-client:2.4.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20220404-2.0.0")

    // State Tracking / Idempotency
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")

    // Logging & Terminal UX
    implementation("org.slf4j:slf4j-simple:2.0.12")
    implementation("me.tongfei:progressbar:0.10.0")
}

application {
    mainClass.set("com.hicksca.mailsync.MailMigratorCli")}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}