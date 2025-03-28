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

package org.ossreviewtoolkit.plugins.packagemanagers.gradlemodel

import OrtDependency

/**
 * The type of this Gradle dependency. In case of a project, it is [projectType]. Otherwise it is "Maven" unless there
 * is no POM, then it is "Unknown".
 */
fun OrtDependency.getIdentifierType(projectType: String) =
    when {
        isProjectDependency -> projectType
        pomFile != null -> "Maven"
        else -> "Unknown"
    }

/**
 * A flag to indicate whether this Gradle dependency refers to a project, or to a package.
 */
val OrtDependency.isProjectDependency: Boolean
    get() = localPath != null
