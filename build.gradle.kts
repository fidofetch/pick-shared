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
