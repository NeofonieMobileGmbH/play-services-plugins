/*
 * Copyright 2025-2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gms.oss.licenses.plugin

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

abstract class EndToEndTestWithFlavors(private val agpVersion: String, private val gradleVersion: String) {

    @get:Rule
    val tempDirectory: TemporaryFolder = TemporaryFolder()

    private fun isBuiltInKotlinEnabled() = agpVersion.startsWith("9.")

    private lateinit var projectDir: File

    private fun createRunner(vararg arguments: String): GradleRunner {
        return createRunnerWithDir(projectDir, *arguments)
    }

    private fun createRunnerWithDir(dir: File, vararg arguments: String): GradleRunner {
        return GradleRunner.create()
            .withProjectDir(dir)
            .withGradleVersion(gradleVersion)
            .forwardOutput()
            // Isolate TestKit per AGP version subclass to allow parallel execution
            // while keeping all metadata inside the project's build directory for cleanliness.
            .withTestKitDir(File(System.getProperty("testkit_path"), this.javaClass.simpleName))
            // Enable strict configuration cache mode for all tests.
            .withArguments(*arguments, "--configuration-cache", "-Dorg.gradle.configuration-cache.problems=fail")
    }

    @Before
    fun setup() {
        projectDir = tempDirectory.newFolder("basicFlavors")
        setupProject(projectDir)
    }

    private fun setupProject(dir: File) {
        File(dir, "build.gradle").writeText(
            """
            plugins {
                id("com.android.application") version "$agpVersion"
                id("com.google.android.gms.oss-licenses-plugin") version "${System.getProperty("plugin_version")}"
            }
            repositories {
                google()
                mavenCentral()
            }
            android {
                compileSdkVersion = "android-31"
                namespace = "com.example.app"
                flavorDimensions "default"
                buildTypes {
                    debug {
                    }
                    release {
                    }
                }
                productFlavors {
                    flavor1 {
                        isDefault true
                        dimension "default"
                    }
                }                
            }
            dependencies {
                implementation("com.google.android.gms:play-services-oss-licenses:17.0.0")
            }
        """.trimIndent()
        )
        File(dir, "gradle.properties").writeText(
            """
            android.useAndroidX=true
            com.google.protobuf.use_unsafe_pre22_gencode=true
        """.trimIndent()
        )
        File(dir, "settings.gradle").writeText(
            """
            pluginManagement {
                repositories {
                    maven {
                         url = uri("${System.getProperty("repo_path")}")
                    }
                    google()
                    mavenCentral()
                }
            }
            """.trimIndent()
        )
    }

    @Test
    fun basic() {
        val result = createRunner("flavor1ReleaseOssLicensesTask").build()
        Assert.assertEquals(result.task(":collectFlavor1ReleaseDependencies")!!.outcome, TaskOutcome.SUCCESS)
        Assert.assertEquals(result.task(":flavor1ReleaseOssDependencyTask")!!.outcome, TaskOutcome.SUCCESS)
        Assert.assertEquals(result.task(":flavor1ReleaseOssLicensesTask")!!.outcome, TaskOutcome.SUCCESS)
        val dependenciesJson = File(projectDir, "build/generated/third_party_licenses/flavor1Release/dependencies.json")
        Assert.assertEquals(expectedDependenciesJson(isBuiltInKotlinEnabled(), agpVersion), dependenciesJson.readText())

        val metadata =
            File(projectDir, "build/generated/res/flavor1ReleaseOssLicensesTask/raw/third_party_license_metadata")
        Assert.assertEquals(expectedContents(isBuiltInKotlinEnabled()), metadata.readText())
    }

    @Test
    fun testAbsentDependencyReport() {
        val result = createRunner("flavor1DebugOssLicensesTask").build()
        Assert.assertEquals(result.task(":flavor1DebugOssDependencyTask")!!.outcome, TaskOutcome.SUCCESS)
        Assert.assertEquals(result.task(":flavor1DebugOssLicensesTask")!!.outcome, TaskOutcome.SUCCESS)

        val licenses = File(projectDir, "build/generated/res/flavor1DebugOssLicensesTask/raw/third_party_licenses")
        Assert.assertEquals(LicensesTask.ABSENT_DEPENDENCY_TEXT + "\n", licenses.readText())
    }

    @Test
    fun testConfigurationCache() {
        // First run to store the configuration cache
        createRunner("flavor1ReleaseOssLicensesTask").build()

        // Clean to test configuration cache with a clean build
        createRunner("clean").build()

        // Second run to reuse the configuration cache
        val result = createRunner("flavor1ReleaseOssLicensesTask").build()

        Assert.assertTrue(
            result.output.contains("Reusing configuration cache") ||
                result.output.contains("Configuration cache entry reused")
        )
    }

    @Test
    fun testComplexDependencyGraph() {
        // Create a multi-module setup to test configuration cache with complex resolution
        val libDir = tempDirectory.newFolder("lib")
        File(libDir, "build.gradle").writeText(
            """
            plugins { id("com.android.library") }
            android {
                compileSdkVersion = "android-31"
                namespace = "com.example.lib"
            }
            dependencies {
                implementation("com.google.code.gson:gson:2.10.1")
            }
        """.trimIndent()
        )
        File(projectDir, "settings.gradle").appendText("\ninclude ':lib'\nproject(':lib').projectDir = new File('${libDir.absolutePath.replace("\\", "/")}')")

        // Rewrite the main build.gradle to include the project dependency and a forced conflict
        File(projectDir, "build.gradle").writeText(
            """
            plugins {
                id("com.android.application") version "$agpVersion"
                id("com.google.android.gms.oss-licenses-plugin") version "${System.getProperty("plugin_version")}"
            }
            repositories {
                google()
                mavenCentral()
            }
            android {
                compileSdkVersion = "android-31"
                namespace = "com.example.app"
                flavorDimensions "default"
                buildTypes {
                    debug {
                    }
                    release {
                    }
                }
                productFlavors {
                    flavor1 {
                        isDefault true
                        dimension "default"
                    }
                }
            }
            dependencies {
                implementation(project(":lib"))
                // Version conflict: lib uses 2.10.1, we force 2.8.9
                implementation("com.google.code.gson:gson") {
                    version {
                        strictly("2.8.9")
                    }
                }
            }
        """.trimIndent()
        )

        // Run with configuration cache twice to ensure resolution is stable and cacheable
        createRunner("flavor1ReleaseOssLicensesTask").build()

        val result = createRunner("flavor1ReleaseOssLicensesTask").build()

        Assert.assertTrue(
            result.output.contains("Configuration cache entry reused") ||
                result.output.contains("Reusing configuration cache")
        )

        // Verify output exists and contains the forced version's license link
        val licensesFile = File(projectDir, "build/generated/res/flavor1ReleaseOssLicensesTask/raw/third_party_licenses")
        Assert.assertTrue(licensesFile.exists())
        val content = licensesFile.readText()
        // Gson 2.8.9 specifically uses the Apache 2.0 license URL.
        Assert.assertTrue(content.contains("apache.org/licenses/LICENSE-2.0"))
    }

    @Test
    fun testRelocatability() {
        val cacheDir = tempDirectory.newFolder("cache")
        val dir1 = tempDirectory.newFolder("dir1")
        val dir2 = tempDirectory.newFolder("dir2")

        // Helper to populate a directory with the test project
        fun populate(dir: File) {
            // ONLY copy the source files, NEVER the build outputs or local cache state
            projectDir.listFiles()?.forEach { file ->
                if (file.name != "build" && file.name != ".gradle") {
                    file.copyRecursively(File(dir, file.name), overwrite = true)
                }
            }

            // Update the settings.gradle to point to the correct repo path in the new location
            File(dir, "settings.gradle").writeText(
                """
                pluginManagement {
                    repositories {
                        maven {
                             url = uri("${System.getProperty("repo_path")}")
                        }
                        google()
                        mavenCentral()
                    }
                }

                buildCache {
                    local {
                        directory = '${cacheDir.absolutePath.replace("\\", "/")}'
                    }
                }
                """.trimIndent()
            )
        }
        populate(dir1)
        populate(dir2)

        // 1. Run in dir1 to prime the cache
        val result1 = createRunnerWithDir(dir1, "flavor1ReleaseOssLicensesTask", "--build-cache").build()
        Assert.assertEquals(TaskOutcome.SUCCESS, result1.task(":flavor1ReleaseOssLicensesTask")?.outcome)

        // 2. Run in dir2 (different absolute path) and expect FROM-CACHE
        val result2 = createRunnerWithDir(dir2, "flavor1ReleaseOssLicensesTask", "--build-cache").build()

        Assert.assertEquals(
            "LicensesTask should be relocatable",
            TaskOutcome.FROM_CACHE,
            result2.task(":flavor1ReleaseOssLicensesTask")?.outcome
        )
        Assert.assertEquals(
            "DependencyTask should be relocatable",
            TaskOutcome.FROM_CACHE,
            result2.task(":flavor1ReleaseOssDependencyTask")?.outcome
        )
    }
}

class EndToEndTestWithFlavors_AGP74_G75 : EndToEndTestWithFlavors("7.4.2", "7.5.1")
class EndToEndTestWithFlavors_AGP80_G80 : EndToEndTestWithFlavors("8.0.2", "8.0.2")
class EndToEndTestWithFlavors_AGP87_G89 : EndToEndTestWithFlavors("8.7.3", "8.9")
class EndToEndTestWithFlavors_AGP812_G814 : EndToEndTestWithFlavors("8.12.2", "8.14.1")
class EndToEndTestWithFlavors_AGP_STABLE_90_G90 : EndToEndTestWithFlavors("9.0.1", "9.1.0")
class EndToEndTestWithFlavors_AGP_RC_92_G94 : EndToEndTestWithFlavors("9.2.0-rc01", "9.4.1")
