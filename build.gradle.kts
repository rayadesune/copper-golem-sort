plugins {
    `java-library`
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "dev.cgp"
version = "0.8.0"

// --- Supported Minecraft versions ----------------------------------------
// 26.3 is the floor as of 0.6.0. The reason is not cosmetic: same-category
// deposits cannot be done correctly before it. The only pre-26.3 hook,
// ItemTransportingEntityValidateTargetEvent, runs while the golem is merely
// choosing which container to walk to - letting an item past it makes the
// golem walk over and come back empty-handed, because vanilla itself only
// ever accepts a container that is empty or already holds the exact item.
// 26.3 adds ItemTransportingEntitySortEvent (PaperMC/Paper#13811), which is
// the actual "does this item belong in this container" decision, so it can
// be answered once and honestly. Support for 1.21.9 - 26.2 is dropped rather
// than kept as a second, worse code path.
val supportedVersions = linkedMapOf(
    "26.3" to "26.3.build.+",
)

/**
 * Which version's API to compile against. There is exactly one supported
 * version now, so this only exists for the day a second entry comes back:
 *
 *   ./gradlew build -PmcVersion=26.4
 */
val mcVersion = providers.gradleProperty("mcVersion").getOrElse(supportedVersions.keys.first())
val paperApiVersion = supportedVersions[mcVersion]
    ?: error("Unsupported Minecraft version: $mcVersion (expected one of ${supportedVersions.keys.joinToString(", ")})")

/**
 * Which version runServer launches. Kept independent of the compiled-against
 * version so a jar can still be checked against a newer server without
 * touching the build target:
 *
 *   ./gradlew runServer -PmcVersion=26.3 -PserverVersion=26.4
 */
val serverVersion = providers.gradleProperty("serverVersion").getOrElse(mcVersion)

/** Java a given server version requires: 21 up to 1.21.11, 25 from 26.x on. */
fun javaFor(version: String) = if (version.startsWith("1.")) 21 else 25

// paper-api 26.x publishes Gradle metadata saying "requires Java 25", and its
// classes are class file 69, so the toolchain is pinned to 25 regardless of
// what the matrix says. The emitted bytecode follows the compiled-against
// version: 26.3 therefore yields Java 25, which is what a 26.3 server runs.
val bytecodeVersion = javaFor(mcVersion)

java {
    // Pinned to 25 regardless of the target: reading the paper-api 26.x jars
    // (class file 69) at compile time requires it.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(bytecodeVersion)
}

tasks.processResources {
    // api-version is the oldest server that may load the jar. Since 0.6.0 the
    // floor and the build target are the same version, but the two stay
    // separate inputs so they can diverge again without surprises.
    //
    // Without the two input declarations below, Gradle considers this task
    // up to date when only these values change and reuses the previously
    // generated paper-plugin.yml — a version bump produced a jar named 0.2.0
    // whose descriptor still claimed 0.1.0.
    inputs.property("pluginVersion", project.version)
    inputs.property("apiVersion", supportedVersions.keys.first())
    filesMatching("paper-plugin.yml") {
        expand(
            "version" to project.version,
            "apiVersion" to supportedVersions.keys.first(),
        )
    }
}

tasks.runServer {
    minecraftVersion(serverVersion)
    // A world opened by a recent version can never be reopened by an older
    // one, so each test version gets its own directory.
    runDirectory.set(layout.projectDirectory.dir("run/$serverVersion"))
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(javaFor(serverVersion)))
        }
    )
}
