import gradlebuild.configureAsRuntimeJarClasspath
import gradlebuild.modules.extension.ExternalModulesExtension
import gradlebuild.packaging.tasks.ExtractJavaAbi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.CompilerPluginConfig
import org.jetbrains.kotlin.gradle.plugin.FilesSubpluginOption
import org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile

/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Common configuration for everything that belongs to the Gradle distribution
plugins {
    id("gradlebuild.task-properties-validation")
    id("gradlebuild.dependency-modules")
}

val libs = project.the<ExternalModulesExtension>()

// Disallow Groovy production code in distribution modules
pluginManager.withPlugin("groovy") {
    tasks.named<GroovyCompile>("compileGroovy") {
        doFirst {
            throw Exception("You must not add Groovy production code in a distribution module")
        }
    }
}

configurations.consumable("apiStubElements") {
    extendsFrom(configurations.named("implementation").get())
    extendsFrom(configurations.named("compileOnly").get())
    attributes {
        // Only use the category attribute, to make sure this cannot be confused with anything else
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named("api-stubs"))
    }
}

pluginManager.withPlugin("gradlebuild.java-library") {
    val extractorClasspathConfig by configurations.creating

    dependencies {
        extractorClasspathConfig(project(":java-api-extractor"))
        extractorClasspathConfig(platform(project(":distributions-dependencies")))
    }

    val extractJavaAbi by tasks.registering(ExtractJavaAbi::class) {
        classesDirectories = sourceSets.main.get().output.classesDirs
        outputDirectory = layout.buildDirectory.dir("generated/java-abi")
        extractorClasspath = extractorClasspathConfig
    }

    configurations {
        named("apiStubElements") {
            outgoing.artifact(extractJavaAbi)
        }
    }
}

pluginManager.withPlugin("gradlebuild.kotlin-library") {
    val apiGenDependencies = configurations.dependencyScope("apiGen")
    val apiGenClasspath = configurations.resolvable("apiGenClasspath") {
        extendsFrom(apiGenDependencies.get())
        configureAsRuntimeJarClasspath(objects)
    }

    dependencies {
        apiGenDependencies(libs.kotlinJvmAbiGenEmbeddable)
    }

    val abiClassesDirectory = layout.buildDirectory.dir("generated/kotlin-abi")
    configure<KotlinJvmProjectExtension> {
        target.compilations.named("main") {
            compileTaskProvider.configure {
                this as BaseKotlinCompile // TODO: Is there a way we can avoid a cast here?
                pluginClasspath.from(apiGenClasspath)
                outputs.dir(abiClassesDirectory)
                    .withPropertyName("abiClassesDirectory")
                pluginOptions.add(provider {
                    CompilerPluginConfig().apply {
                        addPluginArgument(
                            "org.jetbrains.kotlin.jvm.abi", FilesSubpluginOption(
                                "outputDir", listOf(abiClassesDirectory.get().asFile)
                            )
                        )
                    }
                })
            }
        }

        configurations {
            named("apiStubElements") {
                outgoing.artifact(abiClassesDirectory) {
                    builtBy(target.compilations.named("main").flatMap { it.compileTaskProvider })
                }
            }
        }
    }
}
