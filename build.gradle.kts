// Shared by the engine (fidofetch/voxel) and World Seed (fidofetch/world-builder). The apps build it from a folder
// beside theirs (includeBuild("../pick-shared")), so a change here is used by both on their next build.
//     ui       the look and the controls: Theme, Ui widgets, UiFonts (font, Text size), Motion, the Feedback panel
//     console  the command console and the agent hook (docs/console.md)
subprojects {
    apply(plugin = "java-library")
    group = "com.pick"
    version = "0.1.0"
    repositories { mavenCentral() }
    tasks.withType<JavaCompile>().configureEach { options.release = 21 }
    extensions.configure<BasePluginExtension> { archivesName = "pick-" + project.name }   // pick-ui.jar, pick-console.jar in an app's lib/
    dependencies {
        "api"("io.github.spair:imgui-java-binding:1.92.7.1")
        // GLFW for UiFonts.contentScale; each app brings LWJGL itself, with its natives
        "compileOnly"(platform("org.lwjgl:lwjgl-bom:3.4.3"))
        "compileOnly"("org.lwjgl:lwjgl-glfw")
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

project(":console") {
    dependencies { "api"(project(":ui")) }
}

// The start page (docs/launcher.md): opens the engine, the Builder or the Studio. ./gradlew :launcher:run
project(":launcher") {
    apply(plugin = "application")
    extensions.configure<JavaApplication> { mainClass = "com.pick.launcher.Launcher"; applicationName = "worldseed-launcher" }
    val os = (System.getProperty("os.name").lowercase().let { if (it.contains("win")) "windows" else if (it.contains("mac")) "macos" else "linux" })
    val natives = "natives-$os"
    dependencies {
        "implementation"(project(":console"))
        "implementation"(platform("org.lwjgl:lwjgl-bom:3.4.3"))
        "implementation"("org.lwjgl:lwjgl")
        "implementation"("org.lwjgl:lwjgl-glfw")
        "implementation"("org.lwjgl:lwjgl-opengl")
        "runtimeOnly"("org.lwjgl:lwjgl::$natives")
        "runtimeOnly"("org.lwjgl:lwjgl-glfw::$natives")
        "runtimeOnly"("org.lwjgl:lwjgl-opengl::$natives")
        "implementation"("io.github.spair:imgui-java-lwjgl3:1.92.7.1")
        "runtimeOnly"("io.github.spair:imgui-java-natives-$os:1.92.7.1")
    }
    tasks.named<JavaExec>("run") {
        System.getProperties().forEach { k, v -> if (k.toString().startsWith("launcher.")) systemProperty(k.toString(), v) }
    }
}
