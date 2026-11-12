import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.1"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Draws the pairing QR code the phone scans (encoding only; ZXing is also what reads it back in the tests).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.example.a2desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Mimi"
            packageVersion = "1.0.0"
            description = "Desktop companion to the Mimi phone app"
            // Ship the complete Java runtime rather than a trimmed one. A trimmed runtime is the
            // classic reason a packaged Compose app starts fine from Gradle and then dies at launch
            // with a missing-class error (the phone link needs java.net.http, Compose needs several
            // desktop modules). The few extra MB are worth never hitting that.
            includeAllModules = true
            windows {
                iconFile.set(project.file("packaging/a2-desktop.ico"))
                menuGroup = "Mimi"
                perUserInstall = true
            }
        }
    }
}

tasks.test {
    // Tests cause failures on purpose; keep their log entries out of the real %APPDATA% crash.log.
    systemProperty("a2desktop.logDir", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
}

// Pairs this PC with the phone from the command line — same code as the Phone page's button:
//   gradlew pair -Pa2.address="192.168.0.16:47321" -Pa2.key="ABCD-EFGH-JKLM-NPQR-STUV"
tasks.register<JavaExec>("pair") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.example.a2desktop.tools.PairCliKt"
    doFirst { args(providers.gradleProperty("a2.address").orElse("").get(), providers.gradleProperty("a2.key").orElse("").get()) }
}

// Draws the app icon (window icon + Windows .ico) from the app's own theme — see IconRenderer.kt.
tasks.register<JavaExec>("renderIcon") {
    group = "build setup"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.example.a2desktop.IconRendererKt"
    args(projectDir.absolutePath)
}

// Renders every page to a PNG (no window needed) — used to check the layout without a screen.
tasks.register<JavaExec>("renderPreviews") {
    group = "verification"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.example.a2desktop.PreviewRendererKt"
    args(layout.buildDirectory.dir("previews").get().asFile.absolutePath)
}

// Interop test: when the phone project sits next to this one on the PC, its REAL link server, crypto
// and protocol files are compiled into the tests, and the desktop client is run against them. That
// is what catches the two copies of the protocol drifting apart. Skipped if the phone project isn't
// found (the build still works without it).
val phoneMain = rootDir.resolve("../../AndroidStudioProjects/a2/app/src/main/java/com/example/a2")
val phoneLinkSources = phoneMain.resolve("link")
val phoneDreamSources = phoneMain.resolve("ui/dreams")
if (phoneLinkSources.isDirectory) {
    val copyPhoneLink = tasks.register<Sync>("copyPhoneLinkSources") {
        from(phoneLinkSources) {
            include("DesktopLinkServer.kt", "LinkCrypto.kt", "LinkProtocol.kt", "QrPairing.kt")
            into("com/example/a2/link")
        }
        // The skin-care and supplements planners and record, shared with the desktop's own copies (the interop test compares them).
        from(phoneMain.resolve("care")) {
            include("SkinCare.kt", "Supplements.kt", "WorkSchedule.kt", "CareState.kt")
            into("com/example/a2/care")
        }
        // Shared with the desktop's own copy; the interop test fails if the two ever differ.
        from(phoneDreamSources) {
            include("DreamClipboardLogic.kt")
            into("com/example/a2/ui/dreams")
        }
        into(layout.buildDirectory.dir("phone-link"))
    }
    kotlin.sourceSets.named("test") {
        kotlin.srcDir(layout.buildDirectory.dir("phone-link"))
        kotlin.srcDir("src/interopTest/kotlin")
    }
    tasks.named("compileTestKotlin") { dependsOn(copyPhoneLink) }
}
