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

package org.gradle.integtests.internal.component

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
/**
 * These tests demonstrate the behavior of the [SelectionFailureHandler] when a project has various
 * variant selection failures.
 */
class SelectionFailureHandlerIntegrationTest extends AbstractIntegrationSpec {
    def "demonstrate project ambiguous variant selection failure"() {
        buildKotlinFile << """
            ${setupAmbiguousVariantSelectionFailureForProject()}
            ${forceConsumerResolution()}
        """

        expect:
        fails "forceResolution"
        failure.assertHasDescription("Could not determine the dependencies of task ':forceResolution'.")
        failure.assertHasErrorOutput("The consumer was configured to find attribute 'color' with value 'blue'. However we cannot choose between the following variants of project ::")
    }

    def "demonstrate incompatible graph variants selection failure"() {
        buildKotlinFile << """
            ${setupIncompatibleVariantsSelectionFailureForProject()}
            ${forceConsumerResolution()}
        """

        expect:
        fails "outgoingVariants", "forceResolution"
        failure.assertHasDescription("Could not determine the dependencies of task ':forceResolution'.")
        failure.assertHasErrorOutput("Incompatible because this component declares attribute 'color' with value 'blue' and the consumer needed attribute 'color' with value 'green'")
    }

    private String setupAmbiguousVariantSelectionFailureForProject() {
        return """
            val color = Attribute.of("color", String::class.java)
            val shape = Attribute.of("shape", String::class.java)

            configurations {
                consumable("blueRoundElements") {
                    attributes.attribute(color, "blue")
                    attributes.attribute(shape, "round")
                    outgoing.artifact(file("a1.jar"))
                }
                consumable("blueSquareElements") {
                    attributes.attribute(color, "blue")
                    attributes.attribute(shape, "square")
                    outgoing.artifact(file("a2.jar"))
                }

                dependencyScope("blueFilesDependencies")

                resolvable("resolveMe") {
                    extendsFrom(configurations.getByName("blueFilesDependencies"))
                    attributes.attribute(color, "blue")
                }
            }

            dependencies {
                add("blueFilesDependencies", project(":"))
            }
        """
    }

    private String setupIncompatibleVariantsSelectionFailureForProject() {
        return """
            plugins {
                id("base")
            }

            val color = Attribute.of("color", String::class.java)

            configurations {
                val default by getting {
                    attributes.attribute(color, "blue")
                }

                dependencyScope("defaultDependencies")

                resolvable("resolveMe") {
                    extendsFrom(configurations.getByName("defaultDependencies"))
                    attributes.attribute(color, "green")
                }
            }

            dependencies {
                add("defaultDependencies", project(":"))
            }
        """
    }

    private String forceConsumerResolution() {
        return """
            val forceResolution by tasks.registering {
                inputs.files(configurations.getByName("resolveMe"))
            }
        """
    }
}
