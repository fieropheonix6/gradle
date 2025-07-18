/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.tasks.wrapper;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Incubating;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.api.tasks.options.OptionValues;
import org.gradle.api.tasks.wrapper.internal.GradleVersionResolver;
import org.gradle.api.tasks.wrapper.internal.WrapperDefaults;
import org.gradle.api.tasks.wrapper.internal.WrapperGenerator;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.instrumentation.api.annotations.ToBeReplacedByLazyProperty;
import org.gradle.util.GradleVersion;
import org.gradle.util.internal.GUtil;
import org.gradle.util.internal.WrapperDistributionUrlConverter;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.wrapper.Download;
import org.gradle.wrapper.Logger;
import org.gradle.wrapper.WrapperExecutor;
import org.jspecify.annotations.Nullable;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * <p>Generates scripts (for *nix and windows) which allow you to build your project with Gradle, without having to
 * install Gradle.
 *
 * <p>When a user executes a wrapper script the first time, the script downloads and installs the appropriate Gradle
 * distribution and runs the build against this downloaded distribution. Any installed Gradle distribution is ignored
 * when using the wrapper scripts.
 *
 * <p>The scripts generated by this task are intended to be committed to your version control system. This task also
 * generates a small {@code gradle-wrapper.jar} bootstrap JAR file and properties file which should also be committed to
 * your VCS. The scripts delegate to this JAR.
 */
@DisableCachingByDefault(because = "Updating the wrapper is not worth caching")
public abstract class Wrapper extends DefaultTask {
    public static final String DEFAULT_DISTRIBUTION_PARENT_NAME = WrapperDefaults.DISTRIBUTION_PATH;

    /**
     * Specifies the Gradle distribution type.
     */
    public enum DistributionType {
        /**
         * binary-only Gradle distribution without sources and documentation
         */
        BIN,
        /**
         * complete Gradle distribution with binaries, sources and documentation
         */
        ALL
    }

    /**
     * Specifies how the wrapper path should be interpreted.
     */
    public enum PathBase {
        PROJECT, GRADLE_USER_HOME
    }


    private final GradleVersionResolver gradleVersionResolver;

    private Object scriptFile = WrapperDefaults.SCRIPT_PATH;
    private Object jarFile = WrapperDefaults.JAR_FILE_PATH;
    private String distributionPath = DEFAULT_DISTRIBUTION_PARENT_NAME;
    private PathBase distributionBase = WrapperDefaults.DISTRIBUTION_BASE;
    private String distributionUrl;
    private String distributionSha256Sum;
    private DistributionType distributionType = WrapperDefaults.DISTRIBUTION_TYPE;
    private String archivePath = WrapperDefaults.ARCHIVE_PATH;
    private PathBase archiveBase = WrapperDefaults.ARCHIVE_BASE;
    private final Property<Integer> networkTimeout = getProject().getObjects().property(Integer.class);
    private boolean distributionUrlConfigured = false;
    private final boolean isOffline = getProject().getGradle().getStartParameter().isOffline();

    public Wrapper() {
        getValidateDistributionUrl().convention(WrapperDefaults.VALIDATE_DISTRIBUTION_URL);

        gradleVersionResolver = new GradleVersionResolver(getProject().getResources().getText());
    }

    @TaskAction
    void generate() {
        File jarFileDestination = getJarFile();
        File unixScript = getScriptFile();
        FileResolver resolver = getFileLookup().getFileResolver(unixScript.getParentFile());
        String jarFileRelativePath = resolver.resolveAsRelativePath(jarFileDestination);
        File propertiesFile = getPropertiesFile();
        Properties existingProperties = propertiesFile.exists() ? GUtil.loadProperties(propertiesFile) : null;

        checkProperties(existingProperties);
        validateDistributionUrl(propertiesFile.getParentFile());

        WrapperGenerator.generate(
            archiveBase, archivePath,
            distributionBase, distributionPath,
            getDistributionSha256Sum(existingProperties),
            propertiesFile,
            jarFileDestination, jarFileRelativePath,
            unixScript, getBatchScript(),
            getDistributionUrl(),
            getValidateDistributionUrl().get(),
            networkTimeout.getOrNull()
        );
    }

    private void checkProperties(Properties existingProperties) {
        String checksumProperty = existingProperties != null
            ? existingProperties.getProperty(WrapperExecutor.DISTRIBUTION_SHA_256_SUM, null)
            : null;

        if (!isCurrentVersion() &&
            distributionSha256Sum == null &&
            checksumProperty != null) {
            throw new GradleException("gradle-wrapper.properties contains distributionSha256Sum property, but the wrapper configuration does not have one. Specify one in the wrapper task configuration or with the --gradle-distribution-sha256-sum task option");
        }
    }

    private static final String DISTRIBUTION_URL_EXCEPTION_MESSAGE = "Test of distribution url %s failed. Please check the values set with --gradle-distribution-url and --gradle-version.";

    private void validateDistributionUrl(File uriRoot) {
        if (distributionUrlConfigured && getValidateDistributionUrl().get()) {
            String url = getDistributionUrl();
            URI uri = getDistributionUri(uriRoot, url);
            if (uri.getScheme().equals("file")) {
                if (!Files.exists(Paths.get(uri).toAbsolutePath())) {
                    throw UncheckedException.throwAsUncheckedException(new IOException(String.format(DISTRIBUTION_URL_EXCEPTION_MESSAGE, url)), true);
                }
            } else if (uri.getScheme().startsWith("http") && !isOffline) {
                try {
                    new Download(new Logger(true), "gradlew", Download.UNKNOWN_VERSION).sendHeadRequest(uri);
                } catch (Exception e) {
                    throw UncheckedException.throwAsUncheckedException(new IOException(String.format(DISTRIBUTION_URL_EXCEPTION_MESSAGE, url), e), true);
                }
            }
        }
    }

    private static URI getDistributionUri(File uriRoot, String url) {
        try {
            return WrapperDistributionUrlConverter.convertDistributionUrl(url, uriRoot);
        } catch (URISyntaxException e) {
            throw new GradleException("Distribution URL String cannot be parsed: " + url, e);
        }
    }

    private String getDistributionSha256Sum(Properties existingProperties) {
        if (distributionSha256Sum != null) {
            return distributionSha256Sum;
        } else if (isCurrentVersion() && existingProperties != null) {
            return existingProperties.getProperty(WrapperExecutor.DISTRIBUTION_SHA_256_SUM, null);
        } else {
            return null;
        }
    }

    /**
     * Returns the file to write the wrapper script to.
     */
    @OutputFile
    @ToBeReplacedByLazyProperty
    public File getScriptFile() {
        return getServices().get(FileOperations.class).file(scriptFile);
    }

    /**
     * The file to write the wrapper script to.
     *
     * @since 4.0
     */
    public void setScriptFile(File scriptFile) {
        this.scriptFile = scriptFile;
    }

    /**
     * The file to write the wrapper script to.
     */
    public void setScriptFile(Object scriptFile) {
        this.scriptFile = scriptFile;
    }

    /**
     * Returns the file to write the wrapper batch script to.
     */
    @OutputFile
    @ToBeReplacedByLazyProperty
    public File getBatchScript() {
        return WrapperGenerator.getBatchScript(getScriptFile());
    }

    /**
     * Returns the file to write the wrapper jar file to.
     */
    @OutputFile
    @ToBeReplacedByLazyProperty
    public File getJarFile() {
        return getServices().get(FileOperations.class).file(jarFile);
    }

    /**
     * The file to write the wrapper jar file to.
     *
     * @since 4.0
     */
    public void setJarFile(File jarFile) {
        this.jarFile = jarFile;
    }

    /**
     * The file to write the wrapper jar file to.
     */
    public void setJarFile(Object jarFile) {
        this.jarFile = jarFile;
    }

    /**
     * Returns the file to write the wrapper properties to.
     */
    @OutputFile
    @ToBeReplacedByLazyProperty
    public File getPropertiesFile() {
        return WrapperGenerator.getPropertiesFile(getJarFile());
    }

    /**
     * Returns the path where the gradle distributions needed by the wrapper are unzipped. The path is relative to the
     * distribution base directory
     *
     * @see #setDistributionPath(String)
     */
    @Input
    @ToBeReplacedByLazyProperty
    public String getDistributionPath() {
        return distributionPath;
    }

    /**
     * Sets the path where the gradle distributions needed by the wrapper are unzipped. The path is relative to the
     * distribution base directory
     *
     * @see #setDistributionPath(String)
     */
    public void setDistributionPath(String distributionPath) {
        this.distributionPath = distributionPath;
    }

    /**
     * Returns the gradle version for the wrapper.
     *
     * @throws GradleException if the label that can be provided via {@link #setGradleVersion(String)} can not be resolved at the moment. For example, there is not a `release-candidate` available at all times.
     * @see #setGradleVersion(String)
     */
    @Input
    @ToBeReplacedByLazyProperty
    public String getGradleVersion() {
        return getResolvedGradleVersion().getVersion();
    }

    /**
     * The version of the gradle distribution required by the wrapper.
     * This is usually the same version of Gradle you use for building your project.
     * The following labels are allowed to specify a version: {@code latest}, {@code release-candidate}, {@code release-milestone}, {@code release-nightly}, and {@code nightly}
     *
     * <p>The resulting distribution url is validated before it is written to the gradle-wrapper.properties file.
     */
    @Option(option = "gradle-version", description = "The version of the Gradle distribution required by the wrapper. " +
        "The following labels are allowed: latest, release-candidate, release-milestone, release-nightly, and nightly.")
    public void setGradleVersion(String gradleVersion) {
        distributionUrlConfigured = true;
        setUnresolvedGradleVersion(gradleVersion);
    }

    /**
     * Returns the type of the Gradle distribution to be used by the wrapper.
     *
     * @see #setDistributionType(DistributionType)
     */
    @Input
    @ToBeReplacedByLazyProperty
    public DistributionType getDistributionType() {
        return distributionType;
    }

    /**
     * The type of the Gradle distribution to be used by the wrapper. By default, this is {@link DistributionType#BIN},
     * which is the binary-only Gradle distribution without documentation.
     *
     * @see DistributionType
     */
    @Option(option = "distribution-type", description = "The type of the Gradle distribution to be used by the wrapper.")
    public void setDistributionType(DistributionType distributionType) {
        this.distributionType = distributionType;
    }

    /**
     * The list of available gradle distribution types.
     */
    @ToBeReplacedByLazyProperty(comment = "Not supported yet", issue = "https://github.com/gradle/gradle/issues/29341")
    @OptionValues("distribution-type")
    public List<DistributionType> getAvailableDistributionTypes() {
        return Arrays.asList(DistributionType.values());
    }

    /**
     * The URL to download the gradle distribution from.
     *
     * <p>If not set, the download URL is the default for the specified {@link #getGradleVersion()}.
     *
     * <p>If {@link #getGradleVersion()} is not set, will return null.
     *
     * <p>The wrapper downloads a certain distribution only once and caches it. If your distribution base is the
     * project, you might submit the distribution to your version control system. That way no download is necessary at
     * all. This might be in particular interesting, if you provide a custom gradle snapshot to the wrapper, because you
     * don't need to provide a download server then.
     */
    @Input
    @ToBeReplacedByLazyProperty
    public String getDistributionUrl() {
        if (distributionUrl != null) {
            return distributionUrl;
        }

        return WrapperGenerator.getDistributionUrl(getResolvedGradleVersion(), distributionType);
    }

    private boolean isCurrentVersion() {
        return GradleVersion.current().equals(getResolvedGradleVersion());
    }

    private GradleVersion getResolvedGradleVersion() {
        return gradleVersionResolver.getGradleVersion();
    }

    private void setUnresolvedGradleVersion(String gradleVersion) {
        this.gradleVersionResolver.setGradleVersionRequest(gradleVersion);
    }

    /**
     * The URL to download the gradle distribution from.
     *
     * <p>If not set, the download URL is the default for the specified {@link #getGradleVersion()}.
     *
     * <p>If {@link #getGradleVersion()} is not set, will return null.
     *
     * <p>The wrapper downloads a certain distribution and caches it. If your distribution base is the
     * project, you might submit the distribution to your version control system. That way no download is necessary at
     * all. This might be in particular interesting, if you provide a custom gradle snapshot to the wrapper, because you
     * don't need to provide a download server then.
     *
     * <p>The distribution url is validated before it is written to the gradle-wrapper.properties file.
     */
    @Option(option = "gradle-distribution-url", description = "The URL to download the Gradle distribution from.")
    public void setDistributionUrl(String url) {
        distributionUrlConfigured = true;
        this.distributionUrl = url;
    }

    /**
     * The SHA-256 hash sum of the gradle distribution.
     *
     * <p>If not set, the hash sum of the gradle distribution is not verified.
     *
     * <p>The wrapper allows for verification of the downloaded Gradle distribution via SHA-256 hash sum comparison.
     * This increases security against targeted attacks by preventing a man-in-the-middle attacker from tampering with
     * the downloaded Gradle distribution.
     *
     * @since 4.5
     */
    @Nullable
    @Optional
    @Input
    @ToBeReplacedByLazyProperty
    public String getDistributionSha256Sum() {
        return distributionSha256Sum;
    }

    /**
     * The SHA-256 hash sum of the gradle distribution.
     *
     * <p>If not set, the hash sum of the gradle distribution is not verified.
     *
     * <p>The wrapper allows for verification of the downloaded Gradle distribution via SHA-256 hash sum comparison.
     * This increases security against targeted attacks by preventing a man-in-the-middle attacker from tampering with
     * the downloaded Gradle distribution.
     *
     * @since 4.5
     */
    @Option(option = "gradle-distribution-sha256-sum", description = "The SHA-256 hash sum of the gradle distribution.")
    public void setDistributionSha256Sum(@Nullable String distributionSha256Sum) {
        this.distributionSha256Sum = distributionSha256Sum;
    }

    /**
     * The distribution base specifies whether the unpacked wrapper distribution should be stored in the project or in
     * the gradle user home dir.
     */
    @Input
    @ToBeReplacedByLazyProperty
    public PathBase getDistributionBase() {
        return distributionBase;
    }

    /**
     * The distribution base specifies whether the unpacked wrapper distribution should be stored in the project or in
     * the gradle user home dir.
     */
    public void setDistributionBase(PathBase distributionBase) {
        this.distributionBase = distributionBase;
    }

    /**
     * Returns the path where the gradle distributions archive should be saved (i.e. the parent dir). The path is
     * relative to the archive base directory.
     */
    @Input
    @ToBeReplacedByLazyProperty
    public String getArchivePath() {
        return archivePath;
    }

    /**
     * Set's the path where the gradle distributions archive should be saved (i.e. the parent dir). The path is relative
     * to the parent dir specified with {@link #getArchiveBase()}.
     */
    public void setArchivePath(String archivePath) {
        this.archivePath = archivePath;
    }

    /**
     * The archive base specifies whether the unpacked wrapper distribution should be stored in the project or in the
     * gradle user home dir.
     */
    @Input
    @ToBeReplacedByLazyProperty
    public PathBase getArchiveBase() {
        return archiveBase;
    }

    /**
     * The archive base specifies whether the unpacked wrapper distribution should be stored in the project or in the
     * gradle user home dir.
     */
    public void setArchiveBase(PathBase archiveBase) {
        this.archiveBase = archiveBase;
    }

    /**
     * The network timeout specifies how many ms to wait for when the wrapper is performing network operations, such
     * as downloading the wrapper jar.
     *
     * @since 7.6
     */
    @Input
    @Incubating
    @Optional
    @Option(option = "network-timeout", description = "Timeout in ms to use when the wrapper is performing network operations.")
    public Property<Integer> getNetworkTimeout() {
        return networkTimeout;
    }

    /**
     * Indicates if this task will validate the distribution url that has been configured.
     *
     * @return whether this task will validate the distribution url
     * @since 8.2
     */
    @Incubating
    @Input
    @Option(option = "validate-url", description = "Sets task to validate the configured distribution url.")
    public abstract Property<Boolean> getValidateDistributionUrl();

    @Inject
    protected abstract FileLookup getFileLookup();
}
