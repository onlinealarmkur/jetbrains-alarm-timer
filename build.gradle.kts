import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.SignPluginTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(providers.gradleProperty("jdkVersion").get().toInt())
    compilerOptions {
        // Keep the plugin loadable on the minimum supported 2025.2 platform,
        // whose runtime accepts Java 21 bytecode.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        // Kotlin 2.4 requires a non-deprecated 2.2 language level, while the
        // minimum 2025.2 IDE still requires Kotlin API compatibility at 2.1.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.add("-Xwarning-level=DEPRECATED_LANGUAGE_VERSION:disabled")
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    testRuntimeOnly("junit:junit:4.13.2")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
        pluginVerifier("1.410")
        zipSigner("0.1.43")
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    test {
        useJUnitPlatform {
            excludeTags("platform")
        }
    }

    processResources {
        val pluginVersion = project.version.toString()
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("com/onlinealarmkur/jetbrains/plugin-version.properties") {
            expand("pluginVersion" to pluginVersion)
        }
    }

    named("check") {
        dependsOn("platformTest")
    }

    register<Exec>("verifyReleaseContract") {
        group = "verification"
        description = "Runs the release-contract validator test suite."
        inputs.files("scripts/validate-release.sh", "scripts/validate-release-test.sh")
        inputs.files(
            "MARKETPLACE_CHANGE_NOTES.html",
            "README.md",
            "gradle.properties",
            "gradle/verification-metadata.xml",
            ".github/workflows/ci.yml",
            ".github/workflows/release.yml",
        )
        inputs.file("src/main/resources/META-INF/plugin.xml")
        inputs.file("src/main/resources/messages/AlarmTimerBundle.properties")
        outputs.upToDateWhen { false }
        commandLine("bash", "scripts/validate-release-test.sh")
    }

    register("verifyReleaseCandidate") {
        group = "verification"
        description = "Runs every unsigned check required before signing or publishing the plugin."
        dependsOn(
            "check",
            "verifyReleaseContract",
            "verifyPluginProjectConfiguration",
            "verifyPluginStructure",
            "verifyPlugin",
            "buildPlugin",
        )
    }
}

intellijPlatformTesting.testIde {
    register("platformTest") {
        task {
            useJUnitPlatform { includeTags("platform") }
        }
    }
}

val unsignedPluginArchive =
    layout.buildDirectory.file("distributions/jetbrains-${project.version}.zip")
val signedPluginArchive =
    layout.buildDirectory.file("distributions/jetbrains-${project.version}-signed.zip")

tasks.named<SignPluginTask>("signPlugin") {
    archiveFile.set(unsignedPluginArchive)
    signedArchiveFile.set(signedPluginArchive)
    setDependsOn(emptyList<Any>())
}

tasks.named("verifyPluginSignature") {
    dependsOn("signPlugin")
}

val verifySignPluginIsolation = tasks.register("verifySignPluginIsolation") {
    group = "verification"
    description = "Verifies that signing consumes only the already verified release ZIP."

    val signTask = tasks.named<SignPluginTask>("signPlugin").get()
    val dependencies = signTask.taskDependencies.getDependencies(signTask)
    check(dependencies.isEmpty()) {
        "signPlugin must not rebuild the plugin; found dependencies: " +
            dependencies.map { it.path }.sorted().joinToString()
    }

    val expectedArchive = unsignedPluginArchive.get().asFile.toPath().toAbsolutePath().normalize()
    val configuredArchive = signTask.archiveFile.get().asFile.toPath().toAbsolutePath().normalize()
    check(configuredArchive == expectedArchive) {
        "signPlugin must consume $expectedArchive, but is configured for $configuredArchive"
    }
}

tasks.named("verifyReleaseCandidate") {
    dependsOn(verifySignPluginIsolation)
}

val ideSmokeSandbox = layout.projectDirectory.dir(".intellijPlatform/smoke")
val ideSmokeConfig = ideSmokeSandbox.dir("config_runIdeSmoke")
val prepareIdeSmokeSettings = tasks.register<Copy>("prepareIdeSmokeSettings") {
    group = "verification"
    description = "Prepares reproducible settings for the interactive IDE smoke sandbox."
    from(layout.projectDirectory.dir("src/ideSmoke/resources"))
    into(ideSmokeConfig)
    mustRunAfter("prepareSandbox_runIdeSmoke")
}

intellijPlatformTesting.runIde {
    register("runIdeSmoke") {
        sandboxDirectory = ideSmokeSandbox
        task {
            dependsOn(prepareIdeSmokeSettings)
            jvmArgumentProviders.add(
                CommandLineArgumentProvider {
                    listOf("-Dide.no.platform.update=true")
                },
            )
        }
        plugins {
            disablePlugins(
                listOf(
                    "com.jetbrains.station",
                    "intellij.indexing.shared.core",
                    "intellij.jupyter",
                    "org.jetbrains.plugins.kotlin.jupyter",
                ),
            )
        }
    }
}

// Marketplace change notes are release source, not a build literal: the body
// lives in a versioned repository file whose header must match pluginVersion,
// so a new release cannot silently ship the previous release's notes.
val marketplaceChangeNotesFile = layout.projectDirectory.file("MARKETPLACE_CHANGE_NOTES.html")
val marketplaceChangeNotes: String = run {
    val raw = providers.fileContents(marketplaceChangeNotesFile).asText.orNull
        ?: error("MARKETPLACE_CHANGE_NOTES.html is missing or unreadable")
    val lines = raw.trim().lines()
    val header = Regex("<!-- version: (.+) -->").matchEntire(lines.first().trim())
        ?: error("MARKETPLACE_CHANGE_NOTES.html must start with '<!-- version: X.Y.Z -->'")
    val declaredVersion = header.groupValues[1]
    val pluginVersion = project.version.toString()
    check(declaredVersion == pluginVersion) {
        "MARKETPLACE_CHANGE_NOTES.html declares $declaredVersion but pluginVersion is $pluginVersion"
    }
    val body = lines.drop(1).joinToString("\n").trim()
    check(body.isNotBlank()) { "MARKETPLACE_CHANGE_NOTES.html body must not be blank" }
    body
}

intellijPlatform {
    buildSearchableOptions = true

    pluginConfiguration {
        name = "Alarm & Timer"
        version = project.version.toString()
        changeNotes = marketplaceChangeNotes
        ideaVersion {
            sinceBuild = "252"
        }
    }

    pluginVerification {
        ides {
            current()
            create(IntelliJPlatformType.IntellijIdea, "2026.2.1")
            create(IntelliJPlatformType.PyCharm, "2026.2.1")
            create(IntelliJPlatformType.WebStorm, "2026.2.1")
        }
    }

    signing {
        certificateChainFile = layout.buildDirectory.file("signing/chain.crt")
        privateKeyFile = layout.buildDirectory.file("signing/private.pem")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

}
