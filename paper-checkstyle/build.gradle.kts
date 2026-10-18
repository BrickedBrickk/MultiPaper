plugins {
    java
    id("io.papermc.paperweight.paper-checkstyle")
}

paperCheckstyle {
    directoriesToSkipFile = rootProject.layout.projectDirectory.file(".checkstyle/ignored_directories.txt")
    typeUseAnnotationsFile = rootProject.layout.projectDirectory.file(".checkstyle/type_use_annotations.txt")
}

tasks.named<io.papermc.paperweight.checkstyle.tasks.MergeCheckstyleConfigs>("mergeCheckstyleConfigs") {
    baseConfigFile.set(rootProject.layout.projectDirectory.file(".checkstyle/checkstyle_base.xml"))
    overrideConfigFile.set(layout.projectDirectory.file(".checkstyle/checkstyle.xml"))
}

val testData = sourceSets.create("testData")

dependencies {
    implementation("com.puppycrawl.tools:checkstyle:13.8.0")
    implementation("org.jspecify:jspecify:1.0.0")

    testCompileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation(testData.output)

    testImplementation("org.junit.jupiter:junit-jupiter:5.13.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testData.implementationConfigurationName("org.jspecify:jspecify:1.0.0")

    checkstyle(project(":paper-checkstyle"))
}

tasks {
    test {
        useJUnitPlatform()
    }
}
