import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.24"
}

paperweight {
    upstreams.register("purpur") {
        repo = github("PurpurMC", "Purpur")
        ref = providers.gradleProperty("purpurRef")

        patchFile {
            path = "purpur-server/build.gradle.kts"
            outputFile = file("multipaper-server/build.gradle.kts")
            patchFile = file("multipaper-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "purpur-api/build.gradle.kts"
            outputFile = file("multipaper-api/build.gradle.kts")
            patchFile = file("multipaper-api/build.gradle.kts.patch")
        }
        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("multipaper-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("purpurApi") {
            upstreamPath = "purpur-api"
            excludes = listOf("build.gradle.kts", "build.gradle.kts.patch", "paper-patches")
            patchesDir = file("multipaper-api/purpur-patches")
            outputDir = file("purpur-api")
        }
    }
}

allprojects {
    group = providers.gradleProperty("group").get()
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(25)
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<PublishingExtension> {
        repositories {
        }
    }

    // The master modules are plain libraries maintained in this repository;
    // the fork projects (multipaper-api/multipaper-server) configure their own
    // test dependencies from upstream Paper/Purpur.
    if (name == "multipaper-master" || name == "multipaper-mastermessagingprotocol") {
        repositories {
            mavenCentral()
            maven("https://oss.sonatype.org/content/groups/public/")
            maven("https://oss.sonatype.org/content/repositories/snapshots")
            maven("https://repo.velocitypowered.com/snapshots/")
            maven("https://ci.emc.gs/nexus/content/groups/aikar/")
            maven("https://repo.aikar.co/content/groups/aikar")
            maven("https://repo.md-5.net/content/repositories/releases/")
            maven("https://hub.spigotmc.org/nexus/content/groups/public/")
            maven("https://jitpack.io")
        }

        dependencies {
            "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
            "testImplementation"("org.junit.jupiter:junit-jupiter")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "failed", "skipped")
            }
        }
    }

    // MultiPaper fork server module: depends on the master messaging protocol
    // module and needs the zstd-jni library for packet compression.
    if (name == "multipaper-server") {
        repositories {
            mavenCentral()
        }

        dependencies {
            "implementation"("com.github.luben:zstd-jni:1.5.6-9")
            "implementation"("commons-lang:commons-lang:2.6")
            "implementation"("io.methvin:directory-watcher:0.18.0")
        }

        afterEvaluate {
            val messagingProject = rootProject.findProject(":multipaper-mastermessagingprotocol")
            if (messagingProject != null) {
                dependencies {
                    "implementation"(messagingProject)
                }
            }
        }
    }
}

// paperweight 2.0 uses checkoutPurpurRepo output while collecting Purpur ATs.
// Declare the dependency explicitly for Gradle 9.8 validation.
gradle.projectsEvaluated {
    val checkoutPurpur = rootProject.tasks.named("checkoutPurpurRepo")
    val serverProject = project(":multipaper-server")
    serverProject.tasks.configureEach {
        dependsOn(checkoutPurpur)
    }
}
