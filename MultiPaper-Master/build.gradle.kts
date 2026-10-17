version = "${properties["masterVersion"]}"

plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    maven {
        url = uri("https://repo.velocitypowered.com/snapshots/")
    }
}

dependencies {
    implementation(project(":multipaper-mastermessagingprotocol"))
    implementation("org.jetbrains:annotations:26.1.0")
    implementation("org.json:json:20260814")
    implementation("org.yaml:snakeyaml:2.7")
    implementation("io.netty:netty-all:4.2.19.Final")
    implementation("se.llbit:jo-nbt:1.3.0")
    compileOnly("net.md-5:bungeecord-api:1.16-R0.4")
    compileOnly("com.velocitypowered:velocity-api:4.2.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.0")
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "puregero.multipaper.server.MultiPaperServer",
            "Minecraft-Version" to "${properties["mcVersion"]}",
            "Master-Version" to "${properties["masterVersion"]}"
        )
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    relocate("io.netty", "puregero.multipaper.master.libs.netty")
    relocate("org.yaml.snakeyaml", "puregero.multipaper.master.libs.snakeyaml")
    relocate("se.llbit.nbt", "puregero.multipaper.master.libs.nbt")
}

tasks.test {
    dependsOn(tasks.shadowJar)
    // World files are stored relative to the working directory. Run tests in
    // a disposable directory so nothing outside the build directory is touched.
    val testRunDir = layout.buildDirectory.dir("test-run").get().asFile
    workingDir = testRunDir
    doFirst {
        testRunDir.mkdirs()
    }
}

publishing {
    publications.create<MavenPublication>("maven") {
        artifact(tasks.shadowJar)
    }
}
