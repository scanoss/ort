/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.plugins.packagemanagers.maven.utils

import java.io.File

import org.apache.maven.artifact.repository.Authentication
import org.apache.maven.bridge.MavenRepositorySystem
import org.apache.maven.project.MavenProject
import org.apache.maven.repository.Proxy

import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.repository.AuthenticationContext
import org.eclipse.aether.repository.RemoteRepository

import org.ossreviewtoolkit.analyzer.PackageManager.Companion.processProjectVcs
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.Severity
import org.ossreviewtoolkit.model.createAndLogIssue

fun Artifact.identifier() = "$groupId:$artifactId:$version"

/**
 * Return an [Identifier] for this [MavenProject] with the given [projectType].
 */
internal fun MavenProject.identifier(projectType: String): Identifier =
    Identifier(
        type = projectType,
        namespace = groupId,
        name = artifactId,
        version = version
    )

/**
 * Return an internal ID string to uniquely identify this [MavenProject] during the currently ongoing analysis.
 */
internal val MavenProject.internalId: String
    get() = "$groupId:$artifactId:$version"

/**
 * Check whether this collection of [Package]s contains elements that stem from auto-generated POM files. If so,
 * create an [Issue] for each such [Package] using the given [managerName] as source. Optionally, the [targetIssues]
 * list can be provided.
 */
internal fun Collection<Package>.createIssuesForAutoGeneratedPoms(
    managerName: String,
    targetIssues: MutableList<Issue> = mutableListOf()
): List<Issue> =
    mapNotNullTo(targetIssues) { pkg ->
        if (pkg.description == "POM was created by Sonatype Nexus") {
            createAndLogIssue(
                managerName,
                "Package '${pkg.id.toCoordinates()}' seems to use an auto-generated POM which might lack metadata.",
                Severity.HINT
            )
        } else {
            null
        }
    }

/**
 * Convert this [MavenProject] to an ORT [Project] using the given [identifier], the path to the [definitionFile],
 * the [projectDir], and the set of [scopeNames].
 */
internal fun MavenProject.toOrtProject(
    identifier: Identifier,
    definitionFile: File,
    projectDir: File,
    scopeNames: Set<String>
): Project {
    val declaredLicenses = parseLicenses(this)
    val declaredLicensesProcessed = processDeclaredLicenses(declaredLicenses)

    val vcsFromPackage = parseVcsInfo(this)
    val browsableScmUrl = getOriginalScm(this)?.url
    val vcsFallbackUrls = listOfNotNull(browsableScmUrl, this.url).toTypedArray()

    return Project(
        id = identifier,
        definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
        authors = parseAuthors(this),
        declaredLicenses = declaredLicenses,
        declaredLicensesProcessed = declaredLicensesProcessed,
        vcs = vcsFromPackage,
        vcsProcessed = processProjectVcs(projectDir, vcsFromPackage, *vcsFallbackUrls),
        homepageUrl = url.orEmpty(),
        scopeNames = scopeNames
    )
}

/**
 * Convert this [RemoteRepository] to a repository in the format used by the Maven Repository System.
 * Make sure that all relevant properties are set, especially the proxy and authentication.
 */
internal fun RemoteRepository.toArtifactRepository(
    repositorySystemSession: RepositorySystemSession,
    repositorySystem: MavenRepositorySystem,
    id: String
) = repositorySystem.createRepository(url, id, true, null, true, null, null).apply {
    this@toArtifactRepository.proxy?.also { repoProxy ->
        proxy = Proxy().apply {
            host = repoProxy.host
            port = repoProxy.port
            protocol = repoProxy.type
            toMavenAuthentication(
                AuthenticationContext.forProxy(
                    repositorySystemSession,
                    this@toArtifactRepository
                )
            )?.also { authentication ->
                userName = authentication.username
                password = authentication.password
            }
        }
    }

    this@toArtifactRepository.authentication?.also {
        authentication = toMavenAuthentication(
            AuthenticationContext.forRepository(
                repositorySystemSession,
                this@toArtifactRepository
            )
        )
    }
}

/**
 * Return authentication information for an artifact repository based on the given [authContext]. The
 * libraries involved use different approaches to model authentication.
 */
private fun toMavenAuthentication(authContext: AuthenticationContext?): Authentication? =
    authContext?.let {
        Authentication(
            it[AuthenticationContext.USERNAME],
            it[AuthenticationContext.PASSWORD]
        ).apply {
            passphrase = it[AuthenticationContext.PRIVATE_KEY_PASSPHRASE]
            privateKey = it[AuthenticationContext.PRIVATE_KEY_PATH]
        }
    }
