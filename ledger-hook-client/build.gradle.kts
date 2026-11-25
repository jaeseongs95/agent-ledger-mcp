// The light client of the client hook (D-0096): the program a host starts for every tool call, `java -jar ledger-client-hook.jar
// <claude|codex|antigravity>` (and `session-key <server> <author>`, `stop`). It carries the call to the resident hook program
// (module ledger-client-hook, a Spring Boot application) over the loopback and prints what that answers. It starts the resident
// program when there is none. Nothing but java.base and the owner-only file protection of ledger-files (pure JDK) is loaded:
// no Spring, no Jackson, no logging library (HK-056). The shared wire format, the state folder and the address file live in
// this module because both programs read and write them; the resident program depends on this module.
plugins {
    id("java-library")
}

dependencies {
    // Owner-only POSIX/ACL protection of the state folder and its files (pure JDK), shared with the server, the CLI and the key store.
    implementation(project(":ledger-files"))

    testImplementation(libs.archunit)
    // The shared architecture and source rules (no operating system detection, the principle rules).
    testImplementation(testFixtures(project(":ledger-attestation")))
}

tasks.withType<JavaCompile>().configureEach {
    // The client starts once per host call: a string concatenation or a lambda in its path costs an invokedynamic bootstrap
    // (tens of milliseconds of a cold JVM), so the concatenation is compiled to StringBuilder calls.
    options.compilerArgs.add("-XDstringConcat=inline")
}

val clientJarName = "ledger-client-hook.jar"

// The one runnable jar of the client: its classes and the classes of ledger-files, merged (no Spring Boot launcher, which would
// load more than the client needs). The plugin manifests of the three hosts call lib/ledger-client-hook.jar.
val clientJar = tasks.register<Jar>("clientJar") {
    group = "build"
    description = "Builds the runnable light client jar $clientJarName."
    archiveFileName = clientJarName
    destinationDirectory = layout.buildDirectory.dir("client-jar")
    manifest {
        attributes(
            "Main-Class" to "org.pgledger.hookclient.Main",
            // Allows java.lang.foreign (WindowsIsolatedProcess on ACL hosts) without JVM stderr warnings (JEP 472).
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
    // the jars of the runtime classpath are built before they are unpacked
    dependsOn(configurations.runtimeClasspath)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.assemble { dependsOn(clientJar) }

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The jar for the modules that stage or start it (the hook module, the server and system tests): a configuration, so that no
// module reaches into this one's tasks.
val hookClientJar = configurations.create("hookClientJar") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add("hookClientJar", clientJar)
}
