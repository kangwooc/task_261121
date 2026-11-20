plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    group = "dev.task"
    version = "0.0.1"
    repositories { mavenCentral() }

    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            freeCompilerArgs.addAll("-Xannotation-default-target=param-property", "-Xjsr305=strict")
        }
    }

    tasks.withType<Test> { useJUnitPlatform() }

    val bom = rootProject.libs.spring.boot.dependencies
    dependencies { add("implementation", platform(bom)) }

    if (name.endsWith("-service")) {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "org.jetbrains.kotlin.plugin.spring")
        apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
        extensions.configure<org.jetbrains.kotlin.allopen.gradle.AllOpenExtension> {
            annotations("jakarta.persistence.Entity", "jakarta.persistence.MappedSuperclass", "jakarta.persistence.Embeddable")
        }
        dependencies {
            add("implementation", "org.springframework.boot:spring-boot-starter-webmvc")
            add("testImplementation", "org.springframework.boot:spring-boot-starter-webmvc-test")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }
    }
}
