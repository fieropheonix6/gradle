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

package org.gradle.jvm.toolchain.internal;

import org.gradle.api.tasks.Input;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainSpec;

public class JavaToolchainInput {

    private final JavaToolchainSpec spec;

    public JavaToolchainInput(JavaToolchainSpec spec) {
        this.spec = spec;
    }

    @Input
    JavaLanguageVersion getLanguageVersion() {
        return spec.getLanguageVersion().get();
    }

    @Input
    String getVendor() {
        return spec.getVendor().get().toString();
    }

    @Input
    String getImplementation() {
        return spec.getImplementation().get().toString();
    }

}
