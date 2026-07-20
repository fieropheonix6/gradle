/*
 * Copyright 2026 the original author or authors.
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

package org.gradle.architecture.test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.PackageMatchers;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verifies that there are no package cycles between production classes.
 *
 * <p>
 * This test checks all first-party production classes in a single run.
 * It replaces the previous per-project {@code archTest} setup, where the same check
 * ran once in every project (~240 times per build), each run paying the full
 * ArchUnit import cost, see <a href="https://github.com/gradle/gradle-private/issues/4682">gradle-private#4682</a>.
 * </p>
 *
 * <p>
 * Exclusions come in three flavors:
 * </p>
 * <ul>
 * <li>Whole modules with known package cycles, excluded by class file location ({@link #EXCLUDED_MODULES}).</li>
 * <li>Packages (or package subtrees) with known cycles ({@link #EXCLUDE_PATTERNS}, {@code /*} matches a single package, {@code /**} matches a package subtree).</li>
 * <li>Classes matched by fully qualified name prefix (patterns ending in {@code **} without a preceding {@code /}).</li>
 * </ul>
 */
@AnalyzeClasses(packages = "org.gradle")
public class PackageCycleTest {

    /**
     * Modules whose classes are entirely excluded from package cycle detection.
     * These modules had all their classes ({@code org/gradle/**}) excluded in the previous per-project setup.
     * Classes are matched by their class file location, which contains the module build directory.
     */
    private static final List<String> EXCLUDED_MODULES = List.of(
        "core",
        "core-api",
        "dependency-management",
        "platform-base",
        "plugins-model-native",
        "plugins-version-catalog",
        "start-parameter"
    );

    private static final List<String> EXCLUDE_PATTERNS = List.of(
        // :base-diagnostics
        "org/gradle/api/reporting/model/internal/*",
        // :configuration-cache
        "org/gradle/internal/cc/**",
        // :domain-object-collections, :file-operations, :model-core
        "org/gradle/api/internal/**",
        // :java-api-extractor
        "org/gradle/internal/tools/api/impl/*",
        // :kotlin-dsl, :kotlin-dsl-plugins, :kotlin-dsl-provider-plugins
        "org/gradle/kotlin/dsl/**",
        // :model-core
        "org/gradle/model/internal/core/**",
        "org/gradle/model/internal/inspect/**",
        "org/gradle/model/internal/manage/schema/**",
        "org/gradle/model/internal/type/**",
        // :model-core: cycle between org.gradle.api.internal.provider and org.gradle.util.internal
        // (api.internal.provider -> ConfigureUtil, DeferredUtil -> api.internal.provider)
        // :logging
        "org/gradle/util/**",
        // :process-services, :process-memory-services, :worker-process-services
        "org/gradle/process/internal/**",
        // :classpath: pre-existing cycles between classpath subpackages (classpath <-> intercept, classpath <-> transforms)
        "org/gradle/internal/classpath/**",
        // :logging
        "org/gradle/internal/featurelifecycle/**",
        // :native: cycle between public interface, Factory and implementation class in internal package
        "org/gradle/platform/internal/**",
        // :test-kit
        "org/gradle/testkit/runner/internal/**",
        // :base-ide-plugins, :ide, :ide-plugins
        "org/gradle/plugins/ide/idea/**",
        "org/gradle/plugins/ide/internal/*",
        "org/gradle/plugins/ide/eclipse/internal/*",
        "org/gradle/plugins/ide/eclipse/model/internal/*",
        // :problems-api: ProblemId.create() and ProblemGroup.create() return internal types
        "org/gradle/api/problems/**",
        // :tooling-api
        "org/gradle/tooling/**",
        // :javadoc: these public packages have classes that are tangled with the corresponding internal package.
        "org/gradle/external/javadoc/**",
        // :jvm-services, :toolchains-jvm, :toolchains-jvm-shared: needed for the factory methods in the interface
        // since the implementation is in an internal package which in turn references the interface.
        "org/gradle/jvm/toolchain/**",
        // :language-java, :javadoc, :scala, :build-init: these public packages have classes
        // that are tangled with the corresponding internal package.
        "org/gradle/api/tasks/**",
        // :plugins-application, :plugins-java, :plugins-java-base, :antlr, :code-quality, :war, :software-diagnostics
        "org/gradle/api/plugins/**",
        // :scala
        "org/gradle/language/scala/tasks/*",
        // :jacoco
        "org/gradle/internal/jacoco/*",
        "org/gradle/testing/jacoco/plugins/*",
        // :ear
        "org/gradle/plugins/ear/internal/*",
        // :language-native, :plugins-model-native
        "org/gradle/language/nativeplatform/internal/**",
        // :platform-native
        "org/gradle/nativeplatform/plugins/**",
        "org/gradle/nativeplatform/tasks/**",
        "org/gradle/nativeplatform/internal/**",
        "org/gradle/nativeplatform/toolchain/internal/**",
        // :maven
        "org/gradle/api/publication/maven/internal/**",
        "org/gradle/api/artifacts/maven/**",
        // :reporting
        "org/gradle/api/reporting/internal/**",
        // :software-diagnostics
        "org/gradle/api/reporting/dependencies/internal/*",
        // :signing
        "org/gradle/plugins/signing/**",

        // Cross-module package cycles that were invisible to the previous per-project check,
        // because the packages involved span multiple modules:
        // org.gradle.api <-> org.gradle.internal, org.gradle.api.specs <-> org.gradle.internal
        "org/gradle/internal/*",
        // org.gradle.api.specs <-> org.gradle.api.specs.internal
        "org/gradle/api/specs/internal/*",
        // org.gradle.internal.reflect -> org.gradle.model.internal.asm -> org.gradle.internal.classloader -> org.gradle.internal.reflect and
        // org.gradle.internal.reflect -> org.gradle.internal.reflect.validation -> {org.gradle.problems, org.gradle.internal.logging.text} -> org.gradle.internal.reflect
        "org/gradle/internal/reflect/*",
        // org.gradle.internal.service.scopes -> org.gradle.internal.service -> org.gradle.internal.concurrent -> org.gradle.internal.service.scopes
        "org/gradle/internal/service/scopes/*",
        // org.gradle.language.swift (spans :language-native and :platform-native) <-> org.gradle.language.swift.tasks
        "org/gradle/language/swift/tasks/**",
        // org.gradle.launcher.daemon.configuration (spans :client-services, :daemon-protocol and :launcher) <-> org.gradle.launcher.daemon.context
        "org/gradle/launcher/daemon/configuration/*"
    );

    private static final PackageMatchers IGNORED_PACKAGES_FOR_CYCLES = PackageMatchers.of(ignoredPackagesForCycles());
    private static final Set<String> IGNORED_CLASSES_FOR_CYCLES = ignoredClassesForCycles();

    private static boolean isInIgnoredPackage(JavaClass javaClass) {
        return IGNORED_PACKAGES_FOR_CYCLES.test(javaClass.getPackageName());
    }

    private static boolean isIgnoredClass(JavaClass javaClass) {
        return javaClass.isAnnotation() || IGNORED_CLASSES_FOR_CYCLES.stream().anyMatch(prefix -> javaClass.getFullName().startsWith(prefix));
    }

    /**
     * Only first-party production classes participate in cycle detection.
     * First-party classes are loaded from project build directories (either class directories
     * or jars in {@code build/libs}), while third-party classes in {@code org.gradle} packages
     * (e.g. {@code org.gradle.fileevents}) are loaded from the dependency cache.
     */
    private static boolean isFirstPartyClass(JavaClass javaClass) {
        String location = locationOf(javaClass);
        return location.contains("/build/classes/") || location.contains("/build/libs/");
    }

    private static boolean isInExcludedModule(JavaClass javaClass) {
        String location = locationOf(javaClass);
        return EXCLUDED_MODULES.stream().anyMatch(module -> location.contains("/" + module + "/build/"));
    }

    private static String locationOf(JavaClass javaClass) {
        return javaClass.getSource().map(source -> source.getUri().toString()).orElse("");
    }

    private static boolean isClassNamePattern(String pattern) {
        return pattern.endsWith("*") && !(pattern.endsWith("/*") || pattern.endsWith("/**"));
    }

    private static Set<String> ignoredPackagesForCycles() {
        return EXCLUDE_PATTERNS.stream()
            .filter(pattern -> !isClassNamePattern(pattern))
            .map(pattern -> pattern.replace("/**", ".."))
            .map(pattern -> pattern.replace("/*", ""))
            .map(pattern -> pattern.replace("/", "."))
            .collect(Collectors.toSet());
    }

    private static Set<String> ignoredClassesForCycles() {
        return EXCLUDE_PATTERNS.stream()
            .filter(PackageCycleTest::isClassNamePattern)
            .map(pattern -> pattern.replace("/", "."))
            .map(pattern -> pattern.replace("*", ""))
            .collect(Collectors.toSet());
    }

    private static final SliceAssignment GRADLE_SLICE_ASSIGNMENT = new SliceAssignment() {
        @Override
        public String getDescription() {
            return "slices matching 'org.gradle.(**)";
        }

        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            if (!isFirstPartyClass(javaClass) || isInExcludedModule(javaClass) || isInIgnoredPackage(javaClass) || isIgnoredClass(javaClass)) {
                return SliceIdentifier.ignore();
            }
            return SliceIdentifier.of(javaClass.getPackageName());
        }
    };

    @ArchTest
    public static final ArchRule there_are_no_package_cycles =
        SlicesRuleDefinition.slices().assignedFrom(GRADLE_SLICE_ASSIGNMENT)
            .should()
            .beFreeOfCycles();
}
