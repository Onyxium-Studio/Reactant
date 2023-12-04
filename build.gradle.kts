import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.URI

val kotlinVersion = "1.9.20"

group = "dev.reactant"

plugins {
    java
    `maven-publish`
    signing
    kotlin("jvm") version "1.9.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.jetbrains.dokka") version "1.9.10"
    id("com.palantir.git-version") version "3.0.0"
}

group = "dev.reactant"
val gitVersion: groovy.lang.Closure<String> by extra
val versionDetails: groovy.lang.Closure<String> by extra
val details = versionDetails() as com.palantir.gradle.gitversion.VersionDetails
version = details.lastTag + if (!details.isCleanTag && !details.lastTag.endsWith("-SNAPSHOT")) "-SNAPSHOT" else ""
val isRelease = details.isCleanTag && !details.lastTag.endsWith("-SNAPSHOT")

println("Preparing build ${project.name} $version")

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
    kotlinOptions.freeCompilerArgs = listOf("-Xjvm-default=all-compatibility")
}

repositories {
    jcenter()
    mavenCentral()
    maven { url = URI.create("https://hub.spigotmc.org/nexus/content/repositories/snapshots") }
    maven { url = URI.create("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = URI.create("https://oss.sonatype.org/content/repositories/releases/") }
    maven { url = URI.create("https://repo.codemc.org/repository/maven-public") }
}

dependencies {
    listOf(
        "reflect"
    ).forEach { implementation(kotlin(it, kotlinVersion)) }

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0-RC")
    //implementation("org.jetbrains.kotlin:kotlin-script-runtime") // scripting support
    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable")

    implementation("org.bstats:bstats-bukkit:3.0.2") {
        // isTransitive = false
    }

    api("io.reactivex.rxjava3:rxjava:3.1.6")
    api("io.reactivex.rxjava3:rxkotlin:3.0.1")
    api("io.github.classgraph:classgraph:4.8.165")

    api("com.google.code.gson:gson:2.10.1")
    api("org.yaml:snakeyaml:2.0")
    api("com.moandjiezana.toml:toml4j:0.7.2")

    api("info.picocli:picocli:4.7.3")

    api("org.apache.logging.log4j:log4j-core:2.20.0")

    api("com.squareup.retrofit2:retrofit:2.9.0")
    api("com.squareup.retrofit2:adapter-rxjava3:2.9.0")
    api("com.squareup.retrofit2:converter-gson:2.9.0")

    api("com.github.shynixn.mccoroutine:mccoroutine-bukkit-api:2.13.0")
    api("com.github.shynixn.mccoroutine:mccoroutine-bukkit-core:2.13.0")

    compileOnly("org.spigotmc:spigot-api:1.16.4-R0.1-SNAPSHOT")
}

tasks.dokkaJavadoc.configure {
    outputDirectory.set(buildDir.resolve("javadoc"))
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it is Sign }) {
        allprojects {
            extra["signing.keyId"] = System.getenv("SIGNING_KEYID")
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    dependsOn(JavaPlugin.CLASSES_TASK_NAME)
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}

val javadocJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaJavadoc)
    archiveClassifier.set("javadoc")
    from(tasks.javadoc)
}

val shadowJar = (tasks["shadowJar"] as ShadowJar).apply {
    relocate("org.bstats", "dev.reactant.reactant.core")
    relocate("okhttp3", "dev.reactant.reactant.okhttp3")
    relocate("okio", "dev.reactant.reactant.okio")
    relocate("cssparser", "dev.reactant.reactant.cssparser")
    relocate("javassist", "dev.reactant.reactant.javassist")
}

val dokkaJar by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaHtml)
    archiveClassifier.set("dokka")
    from(tasks.dokkaHtml)
}

val deployPlugin by tasks.registering(Copy::class) {
    dependsOn(shadowJar)
    System.getenv("PLUGIN_DEPLOY_PATH")?.let {
        from(shadowJar)
        into(it)
    }
}

val build = (tasks["build"] as Task).apply {
    arrayOf(
        sourcesJar,
        shadowJar,
        deployPlugin
    ).forEach { dependsOn(it) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // artifact(sourcesJar.get())
            // artifact(javadocJar.get())
            // artifact(dokkaJar.get())
            // artifact(shadowJar)

            groupId = group.toString()
            artifactId = project.name
            version = version

            pom {
                name.set("Reactant")
                description.set("An elegant plugin framework for spigot")
                url.set("https://reactant.dev")
                licenses {
                    license {
                        name.set("GPL-3.0")
                        url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                    }
                }
                scm {
                    connection.set("scm:git:git@gitlab.com:reactant/reactant.git")
                    url.set("https://gitlab.com/reactant/reactant/")
                }

                developers {
                    developer {
                        id.set("setako")
                        name.set("Setako")
                        organization.set("Reactant Dev Team")
                        organizationUrl.set("https://gitlab.com/reactant")
                    }
                }
            }
        }
    }

    repositories {
        maven {

            val releasesRepoUrl = "https://oss.sonatype.org/service/local/staging/deploy/maven2"
            val snapshotsRepoUrl = "https://oss.sonatype.org/content/repositories/snapshots"
            url = uri(if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
            credentials {
                username = System.getenv("SONATYPE_USERNAME")
                password = System.getenv("SONATYPE_PASSWORD")
            }
        }
    }
}

if (isRelease) {
    signing {
        ext["signing.keyId"] = findProperty("signingKeyId") as String?
        val signingKey: String? by project
        val signingPassword: String? by project
        useInMemoryPgpKeys(signingKey?.replace("\\n", "\n"), signingPassword)
        sign(publishing.publications["maven"])
    }
}
