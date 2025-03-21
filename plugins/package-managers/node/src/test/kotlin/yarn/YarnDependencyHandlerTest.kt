/*
 * Copyright (C) 2021 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.node.yarn

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.containExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

import java.io.File

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.PackageLinkage

class YarnDependencyHandlerTest : StringSpec({
    "identifierFor extracts the correct identifier" {
        val id = createIdentifier("foo")
        val module = createModuleInfo(id)

        val handler = createHandler()

        handler.identifierFor(module) shouldBe id
    }

    "dependenciesFor returns the correct dependencies" {
        val dep1 = createModuleInfo(createIdentifier("dependency1"))
        val dep2 = createModuleInfo(createIdentifier("dependency2"))
        val dep3 = createModuleInfo(createIdentifier("dependency3"))
        val dependencies = setOf(dep1, dep2, dep3)
        val module = createModuleInfo(createIdentifier("test"), dependencies = dependencies)

        val handler = createHandler()

        handler.dependenciesFor(module) shouldBe dependencies
    }

    "linkageFor returns the correct package linkage" {
        val module = createModuleInfo(createIdentifier("linkageTest"))

        val handler = createHandler()

        handler.linkageFor(module) shouldBe PackageLinkage.DYNAMIC
    }

    "a package can be created for a module" {
        val definitionFile = File("src/test/assets/test-package.json")
        val module = createModuleInfo(createIdentifier("packageTest"), definitionFile)

        val handler = createHandler()

        val pkg = handler.createPackage(module, mutableListOf())

        pkg shouldNotBeNull {
            id shouldBe Identifier("NPM", "", "bonjour", "3.5.0")
            declaredLicenses should containExactly("MIT")
            authors should containExactly("Thomas Watson Steen")
            homepageUrl shouldBe "https://github.com/watson/bonjour/local"
            description shouldBe "A Bonjour/Zeroconf implementation in pure JavaScript (local)"
        }
    }
})

/**
 * Construct a test identifier with the given [name].
 */
private fun createIdentifier(name: String): Identifier =
    Identifier(type = "NPM", namespace = "test", name = name, version = "1.2.3")

/**
 * Convenience function to create an [ModuleInfo] instance with default values based on the provided [id],
 * [packageFile], and [dependencies].
 */
private fun createModuleInfo(
    id: Identifier,
    packageFile: File = File("project/package.json"),
    dependencies: Set<ModuleInfo> = emptySet(),
    isProject: Boolean = false
): ModuleInfo = ModuleInfo(id, packageFile.parentFile, packageFile, dependencies, isProject)

/**
 * Creates an [YarnDependencyHandler] instance to be used by test cases.
 */
private fun createHandler() = YarnDependencyHandler(YarnFactory.create())
