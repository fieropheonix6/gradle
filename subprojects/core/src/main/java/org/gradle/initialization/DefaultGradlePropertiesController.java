/*
 * Copyright 2020 the original author or authors.
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

package org.gradle.initialization;

import com.google.common.collect.ImmutableMap;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier;
import org.gradle.api.internal.project.ProjectIdentity;
import org.gradle.api.internal.properties.GradleProperties;
import org.gradle.initialization.properties.DefaultGradleProperties;
import org.gradle.initialization.properties.GradlePropertiesLoader;
import org.gradle.initialization.properties.SystemPropertiesInstaller;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultGradlePropertiesController implements GradlePropertiesController {

    private final GradlePropertiesLoader gradlePropertiesLoader;
    private final SystemPropertiesInstaller systemPropertiesInstaller;

    private final ConcurrentMap<BuildIdentifier, BuildScopedGradleProperties> buildProperties = new ConcurrentHashMap<>();
    private final ConcurrentMap<ProjectIdentity, ProjectScopedGradleProperties> projectProperties = new ConcurrentHashMap<>();

    public DefaultGradlePropertiesController(
        GradlePropertiesLoader gradlePropertiesLoader,
        SystemPropertiesInstaller systemPropertiesInstaller
    ) {
        this.gradlePropertiesLoader = gradlePropertiesLoader;
        this.systemPropertiesInstaller = systemPropertiesInstaller;
    }

    @Override
    public GradleProperties getGradleProperties(BuildIdentifier buildId) {
        return getOrCreateGradleProperties(buildId);
    }

    @Override
    public void loadGradleProperties(BuildIdentifier buildId, File buildRootDir, boolean setSystemProperties) {
        getOrCreateGradleProperties(buildId).loadProperties(buildRootDir, setSystemProperties);
    }

    @Override
    public void unloadGradleProperties(BuildIdentifier buildId) {
        if (!projectProperties.isEmpty()) {
            throw new IllegalStateException("Cannot unload Gradle properties after loading project properties.");
        }
        getOrCreateGradleProperties(buildId).unload();
    }

    @Override
    public GradleProperties getGradleProperties(ProjectIdentity projectId) {
        return getOrCreateGradleProperties(projectId);
    }

    @Override
    public void loadGradleProperties(ProjectIdentity projectId, File projectDir) {
        LoadedBuildScopedState loadedBuildProperties = getOrCreateGradleProperties(projectId.getBuildIdentifier())
            .checkLoaded();
        getOrCreateGradleProperties(projectId).loadProperties(loadedBuildProperties, projectDir);
    }

    private BuildScopedGradleProperties getOrCreateGradleProperties(BuildIdentifier buildId) {
        return buildProperties.computeIfAbsent(buildId, id ->
            new BuildScopedGradleProperties(gradlePropertiesLoader, systemPropertiesInstaller, id));
    }

    private ProjectScopedGradleProperties getOrCreateGradleProperties(ProjectIdentity projectId) {
        return projectProperties.computeIfAbsent(projectId, id ->
            new ProjectScopedGradleProperties(gradlePropertiesLoader, id));
    }

    private static class BuildScopedGradleProperties implements GradleProperties {

        private final GradlePropertiesLoader loader;
        private final SystemPropertiesInstaller systemPropertiesInstaller;
        private final BuildIdentifier buildId;
        @Nullable
        private volatile LoadedBuildScopedState loaded;

        private BuildScopedGradleProperties(
            GradlePropertiesLoader loader,
            SystemPropertiesInstaller systemPropertiesInstaller,
            BuildIdentifier buildId
        ) {
            this.loader = loader;
            this.systemPropertiesInstaller = systemPropertiesInstaller;
            this.buildId = buildId;
        }

        @Override
        public @Nullable String find(String propertyName) {
            return gradleProperties().find(propertyName);
        }

        @Override
        public Map<String, String> getProperties() {
            return gradleProperties().getProperties();
        }

        @Override
        public Map<String, String> getPropertiesWithPrefix(String prefix) {
            return gradleProperties().getPropertiesWithPrefix(prefix);
        }

        @Override
        public @Nullable Object findUnsafe(String propertyName) {
            return gradleProperties().findUnsafe(propertyName);
        }

        private GradleProperties gradleProperties() {
            return checkLoaded().gradleProperties;
        }

        private LoadedBuildScopedState checkLoaded() {
            LoadedBuildScopedState loaded = this.loaded;
            if (loaded == null) {
                throw new IllegalStateException(String.format("GradleProperties for %s have not been loaded yet.", buildId));
            }
            return loaded;
        }

        private void loadProperties(File buildRootDir, boolean setSystemProperties) {
            LoadedBuildScopedState loaded = this.loaded;
            if (loaded != null) {
                if (loaded.buildRootDir.equals(buildRootDir)) {
                    // Ignore repeated loads from the same location
                    return;
                }
                throw new IllegalStateException(String.format(
                    "GradleProperties has already been loaded from '%s' and cannot be loaded from '%s'.",
                    loaded.buildRootDir, buildRootDir
                ));
            }

            this.loaded = loadNewState(buildRootDir, setSystemProperties);
        }

        private LoadedBuildScopedState loadNewState(File buildRootDir, boolean setSystemProperties) {
            Map<String, String> fromGradleHome = loader.loadFromGradleHome();
            Map<String, String> fromBuildRoot = loader.loadFrom(buildRootDir);
            Map<String, String> fromGradleUserHome = loader.loadFromGradleUserHome();

            if (setSystemProperties) {
                boolean isRootBuild = DefaultBuildIdentifier.ROOT.equals(buildId);
                GradleProperties systemPropertiesSource = new DefaultGradleProperties(mergeMaps(
                    fromGradleHome,
                    fromBuildRoot,
                    fromGradleUserHome
                ));
                systemPropertiesInstaller.setSystemPropertiesFrom(systemPropertiesSource, isRootBuild);
            }

            Map<String, String> fromEnvVariables = loader.loadFromEnvironmentVariables();
            Map<String, String> fromSystemProperties = loader.loadFromSystemProperties();
            Map<String, String> fromStartParamProjectProperties = loader.loadFromStartParameterProjectProperties();

            return LoadedBuildScopedState.from(
                buildRootDir,
                fromGradleHome,
                fromBuildRoot,
                fromGradleUserHome,
                fromEnvVariables,
                fromSystemProperties,
                fromStartParamProjectProperties
            );
        }

        private void unload() {
            this.loaded = null;
        }
    }


    private static class LoadedBuildScopedState {

        private final File buildRootDir;
        private final GradleProperties gradleProperties;
        private final Map<String, String> projectScopedDefaults;
        private final Map<String, String> projectScopedOverrides;

        private LoadedBuildScopedState(
            File buildRootDir,
            GradleProperties gradleProperties,
            Map<String, String> projectScopedDefaults,
            Map<String, String> projectScopedOverrides
        ) {
            this.buildRootDir = buildRootDir;
            this.gradleProperties = gradleProperties;
            this.projectScopedDefaults = projectScopedDefaults;
            this.projectScopedOverrides = projectScopedOverrides;
        }

        public static LoadedBuildScopedState from(
            File buildRootDir,
            Map<String, String> fromGradleHome,
            Map<String, String> fromBuildRoot,
            Map<String, String> fromGradleUserHome,
            Map<String, String> fromEnvVariables,
            Map<String, String> fromSystemProperties,
            Map<String, String> fromStartParamProjectProperties
        ) {
            ImmutableMap<String, String> defaults = mergeMaps(
                fromGradleHome,
                fromBuildRoot
            );

            ImmutableMap<String, String> overrides = mergeMaps(
                fromGradleUserHome,
                fromEnvVariables,
                fromSystemProperties,
                fromStartParamProjectProperties
            );

            ImmutableMap<String, String> buildScopedProperties = mergeMaps(
                defaults,
                overrides
            );

            return new LoadedBuildScopedState(buildRootDir, new DefaultGradleProperties(buildScopedProperties), defaults, overrides);
        }
    }

    private static class ProjectScopedGradleProperties implements GradleProperties {

        private final GradlePropertiesLoader loader;
        private final ProjectIdentity projectId;
        @Nullable
        private volatile GradleProperties loaded;

        private ProjectScopedGradleProperties(GradlePropertiesLoader loader, ProjectIdentity projectId) {
            this.loader = loader;
            this.projectId = projectId;
        }

        @Override
        public @Nullable String find(String propertyName) {
            return gradleProperties().find(propertyName);
        }

        @Override
        public Map<String, String> getProperties() {
            return gradleProperties().getProperties();
        }

        @Override
        public Map<String, String> getPropertiesWithPrefix(String prefix) {
            return gradleProperties().getPropertiesWithPrefix(prefix);
        }

        @Override
        public @Nullable Object findUnsafe(String propertyName) {
            return gradleProperties().findUnsafe(propertyName);
        }

        private GradleProperties gradleProperties() {
            GradleProperties loaded = this.loaded;
            if (loaded == null) {
                throw new IllegalStateException(String.format("GradleProperties for %s have not been loaded yet.", projectId));
            }
            return loaded;
        }

        private void loadProperties(LoadedBuildScopedState loadedBuildProperties, File projectDir) {
            if (this.loaded != null) {
                throw new IllegalStateException(String.format(
                    "GradleProperties has already been loaded for %s",
                    projectId
                ));
            }

            this.loaded = loadNewState(loadedBuildProperties, projectDir);
        }

        private GradleProperties loadNewState(LoadedBuildScopedState loadedBuildProperties, File projectDir) {
            Map<String, String> fromProjectDir = loader.loadFrom(projectDir);

            ImmutableMap<String, String> projectScopedProperties = mergeMaps(
                loadedBuildProperties.projectScopedDefaults,
                fromProjectDir,
                loadedBuildProperties.projectScopedOverrides
            );

            return new DefaultGradleProperties(projectScopedProperties);
        }
    }


    /**
     * Merges multiple maps into an ImmutableMap, with later maps taking precedence over earlier ones.
     */
    @SafeVarargs
    private static ImmutableMap<String, String> mergeMaps(
        Map<String, String>... maps
    ) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Map<String, String> map : maps) {
            builder.putAll(map);
        }
        return builder.buildKeepingLast();
    }
}
