plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.70"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    application
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "FabricMC"}
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("cuchaz:enigma:0.23.1") {
        exclude("net.fabricmc", "cfr")
    }
    implementation("net.fabricmc:stitch:0.6.1")
    implementation("org.tomlj:tomlj:1.0.0")
    implementation("org.ow2.asm:asm:9.1")
    implementation("org.ow2.asm:asm-tree:9.1")
    implementation("net.sf.jopt-simple:jopt-simple:6.0-alpha-3")
}

application {
    mainClassName = "de.skyrising.guardian.gen.MonumentKt"
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).all {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}