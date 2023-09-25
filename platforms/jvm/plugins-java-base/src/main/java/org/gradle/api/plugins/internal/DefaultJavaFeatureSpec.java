/*
 * Copyright 2019 the original author or authors.
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
package org.gradle.api.plugins.internal;

import org.gradle.api.GradleException;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.NamedDomainObjectSet;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.component.AdhocComponentWithVariants;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.plugins.jvm.internal.DefaultJvmFeature;
import org.gradle.api.plugins.jvm.internal.JvmFeatureInternal;
import org.gradle.api.tasks.SourceSet;
import org.gradle.internal.component.external.model.DefaultImmutableCapability;
import org.gradle.internal.component.external.model.ProjectDerivedCapability;
import org.gradle.jvm.component.internal.JvmSoftwareComponentInternal;
import org.gradle.util.internal.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class DefaultJavaFeatureSpec implements FeatureSpecInternal {
    private final String name;
    private final List<Capability> capabilities = new ArrayList<>(1);
    private final ProjectInternal project;

    private SourceSet sourceSet;
    private boolean withJavadocJar = false;
    private boolean withSourcesJar = false;
    private boolean allowPublication = true;

    public DefaultJavaFeatureSpec(String name, ProjectInternal project) {
        this.name = name;
        this.project = project;
    }

    @Override
    public void usingSourceSet(SourceSet sourceSet) {
        this.sourceSet = sourceSet;
    }

    @Override
    public void capability(String group, String name, String version) {
        capabilities.add(new DefaultImmutableCapability(group, name, version));
    }

    @Override
    public void create() {
        NamedDomainObjectSet<JvmSoftwareComponentInternal> jvmComponents = project.getComponents().withType(JvmSoftwareComponentInternal.class);
        if (jvmComponents.size() > 1) {
            String componentNames = CollectionUtils.join(", ", jvmComponents.getNames());
            throw new GradleException("Cannot register feature '" + name + "' because multiple JVM components are present.  These components were found: " + componentNames + ".");
        }

        setupConfigurations(sourceSet);
    }

    @Override
    public void withJavadocJar() {
        withJavadocJar = true;
    }

    @Override
    public void withSourcesJar() {
        withSourcesJar = true;
    }

    @Override
    public void disablePublication() {
        allowPublication = false;
    }

    private void setupConfigurations(SourceSet sourceSet) {
        if (sourceSet == null) {
            throw new InvalidUserCodeException("You must specify which source set to use for feature '" + name + "'");
        }

        if (capabilities.isEmpty()) {
            capabilities.add(new ProjectDerivedCapability(project, name));
        }

        JvmFeatureInternal feature = new DefaultJvmFeature(name, sourceSet, capabilities, project, true, SourceSet.isMain(sourceSet));
        feature.withApi();

    // TODO: #23495 Investigate the implications of using this class without
    //       the java plugin applied, and thus no java component present.
    //       In the long run, all domain objects created by this feature should be
    //       owned by a component. If we do not add them to the default java component,
    //       we should be adding them to a user-provided or new component instead.
        project.getComponents().withType(JvmSoftwareComponentInternal.class).configureEach(component -> {
            AdhocComponentWithVariants adhocComponent = (AdhocComponentWithVariants) component;
            if (withJavadocJar) {
                feature.withJavadocJar();
                Configuration javadocElements = feature.getJavadocElementsConfiguration();
                adhocComponent.addVariantsFromConfiguration(javadocElements, new JavaConfigurationVariantMapping("runtime", true));
            }
            if (withSourcesJar) {
                feature.withSourcesJar();
                Configuration sourcesElements = feature.getSourcesElementsConfiguration();
                adhocComponent.addVariantsFromConfiguration(sourcesElements, new JavaConfigurationVariantMapping("runtime", true));
            }

            if (allowPublication) {
                adhocComponent.addVariantsFromConfiguration(feature.getApiElementsConfiguration(), new JavaConfigurationVariantMapping("compile", true, null));
                adhocComponent.addVariantsFromConfiguration(feature.getRuntimeElementsConfiguration(), new JavaConfigurationVariantMapping("runtime", true, null));
            }
        });
    }
}
