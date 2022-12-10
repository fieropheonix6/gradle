/*
 * Copyright 2022 the original author or authors.
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

package org.gradle.internal.schema;

import org.gradle.internal.properties.annotations.PropertyMetadata;

abstract class AbstractPropertySchema implements PropertySchema {
    private final String qualifiedName;
    private final PropertyMetadata metadata;
    private final boolean optional;

    public AbstractPropertySchema(String qualifiedName, PropertyMetadata metadata, boolean optional) {
        this.qualifiedName = qualifiedName;
        this.metadata = metadata;
        this.optional = optional;
    }

    @Override
    public String getQualifiedName() {
        return qualifiedName;
    }

    @Override
    public boolean isOptional() {
        return optional;
    }

    @Override
    public PropertyMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        AbstractPropertySchema that = (AbstractPropertySchema) o;

        if (optional != that.optional) {
            return false;
        }
        if (!qualifiedName.equals(that.qualifiedName)) {
            return false;
        }
        return metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        int result = qualifiedName.hashCode();
        result = 31 * result + metadata.hashCode();
        result = 31 * result + (optional ? 1 : 0);
        return result;
    }
}
