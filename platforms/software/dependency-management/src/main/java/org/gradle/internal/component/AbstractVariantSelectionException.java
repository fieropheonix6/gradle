/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.internal.component;

import com.google.common.collect.ImmutableList;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.internal.exceptions.ResolutionProvider;
import org.gradle.internal.exceptions.StyledException;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for all attribute matching selection failures occurring at any stage of dependency resolution.
 */
public abstract class AbstractVariantSelectionException extends StyledException implements ResolutionProvider {
    public static final String DEFAULT_MESSAGE_PREFIX = "Review the variant matching algorithm at ";

    private final List<String> resolutions = new ArrayList<>(1); // Usually there is only one resolution

    public AbstractVariantSelectionException(String message, DocumentationRegistry documentationRegistry) {
        this(message, documentationRegistry, null);
    }

    public AbstractVariantSelectionException(String message, DocumentationRegistry documentationRegistry, @Nullable Throwable cause) {
        super(message, cause);
        addResolution(DEFAULT_MESSAGE_PREFIX + documentationRegistry.getDocumentationFor("variant_attributes", "sec:abm_algorithm") + ".");
    }

    /**
     * Adds a resolution to the list of resolutions.
     *
     * Meant to be called during subclass construction, so <strong>must</strong> remain safe to do so by only accessing fields on this type,
     * hence the {@code final} modifier.
     *
     * @param resolution The resolution (suggestion message) to add
     */
    public final void addResolution(String resolution) {
        resolutions.add(resolution);
    }

    @Override
    public ImmutableList<String> getResolutions() {
        return ImmutableList.copyOf(resolutions);
    }
}
