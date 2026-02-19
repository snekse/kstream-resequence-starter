plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "kstream-resequence-starter"

include("resequence-starter", "sample-app")
