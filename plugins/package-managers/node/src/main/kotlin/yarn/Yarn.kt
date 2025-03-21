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

package org.ossreviewtoolkit.plugins.packagemanagers.node.yarn

import java.io.File
import java.lang.invoke.MethodHandles
import java.util.concurrent.ConcurrentHashMap

import kotlin.time.Duration.Companion.days

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonPrimitive

import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.kotlin.loggerOf

import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Issue
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.createAndLogIssue
import org.ossreviewtoolkit.model.readTree
import org.ossreviewtoolkit.model.utils.DependencyGraphBuilder
import org.ossreviewtoolkit.plugins.api.OrtPlugin
import org.ossreviewtoolkit.plugins.api.PluginDescriptor
import org.ossreviewtoolkit.plugins.packagemanagers.node.NodePackageManager
import org.ossreviewtoolkit.plugins.packagemanagers.node.NodePackageManagerType
import org.ossreviewtoolkit.plugins.packagemanagers.node.PackageJson
import org.ossreviewtoolkit.plugins.packagemanagers.node.parsePackageJson
import org.ossreviewtoolkit.plugins.packagemanagers.node.splitNamespaceAndName
import org.ossreviewtoolkit.utils.common.CommandLineTool
import org.ossreviewtoolkit.utils.common.DirectoryStash
import org.ossreviewtoolkit.utils.common.DiskCache
import org.ossreviewtoolkit.utils.common.Os
import org.ossreviewtoolkit.utils.common.alsoIfNull
import org.ossreviewtoolkit.utils.common.collectMessages
import org.ossreviewtoolkit.utils.common.fieldNamesOrEmpty
import org.ossreviewtoolkit.utils.common.isSymbolicLink
import org.ossreviewtoolkit.utils.common.mebibytes
import org.ossreviewtoolkit.utils.common.realFile
import org.ossreviewtoolkit.utils.common.textValueOrEmpty
import org.ossreviewtoolkit.utils.ort.ortDataDirectory

import org.semver4j.RangesList
import org.semver4j.RangesListFactory

private val yarnInfoCache = DiskCache(
    directory = ortDataDirectory.resolve("cache/analyzer/yarn/info"),
    maxCacheSizeInBytes = 100.mebibytes,
    maxCacheEntryAgeInSeconds = 7.days.inWholeSeconds
)

/** Name of the scope with the regular dependencies. */
private const val DEPENDENCIES_SCOPE = "dependencies"

/** Name of the scope with optional dependencies. */
private const val OPTIONAL_DEPENDENCIES_SCOPE = "optionalDependencies"

/** Name of the scope with development dependencies. */
private const val DEV_DEPENDENCIES_SCOPE = "devDependencies"

internal object YarnCommand : CommandLineTool {
    override fun command(workingDir: File?) = if (Os.isWindows) "yarn.cmd" else "yarn"

    override fun getVersionRequirement(): RangesList = RangesListFactory.create("1.3.* - 1.22.*")
}

/**
 * The [Yarn](https://classic.yarnpkg.com/) package manager for JavaScript.
 */
@OrtPlugin(
    displayName = "Yarn",
    description = "The Yarn package manager for JavaScript.",
    factory = PackageManagerFactory::class
)
open class Yarn(override val descriptor: PluginDescriptor = YarnFactory.descriptor) :
    NodePackageManager(NodePackageManagerType.YARN) {
    override val globsForDefinitionFiles = listOf(NodePackageManagerType.DEFINITION_FILE)

    private lateinit var stash: DirectoryStash

    /** Cache for submodules identified by its moduleDir absolutePath */
    private val submodulesCache = ConcurrentHashMap<String, Set<File>>()

    private val rawModuleInfoCache = mutableMapOf<Pair<File, Set<String>>, RawModuleInfo>()

    override val graphBuilder by lazy { DependencyGraphBuilder(YarnDependencyHandler(this)) }

    /**
     * Load the submodule directories of the project defined in [moduleDir].
     */
    private fun loadWorkspaceSubmodules(moduleDir: File): Set<File> {
        val nodeModulesDir = moduleDir.resolve("node_modules")
        if (!nodeModulesDir.isDirectory) return emptySet()

        val searchDirs = nodeModulesDir.walk().maxDepth(1).filter {
            (it.isDirectory && it.name.startsWith("@")) || it == nodeModulesDir
        }

        return searchDirs.flatMapTo(mutableSetOf()) { dir ->
            dir.walk().maxDepth(1).filter {
                it.isDirectory && it.isSymbolicLink() && it != dir
            }
        }
    }

    override fun beforeResolution(
        analysisRoot: File,
        definitionFiles: List<File>,
        analyzerConfig: AnalyzerConfiguration
    ) {
        YarnCommand.checkVersion()

        val directories = definitionFiles.mapTo(mutableSetOf()) { it.resolveSibling("node_modules") }
        stash = DirectoryStash(directories)
    }

    override fun afterResolution(analysisRoot: File, definitionFiles: List<File>) {
        stash.close()
    }

    override fun resolveDependencies(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        analyzerConfig: AnalyzerConfiguration,
        labels: Map<String, String>
    ): List<ProjectAnalyzerResult> =
        try {
            resolveDependenciesInternal(analysisRoot, definitionFile, excludes, analyzerConfig.allowDynamicVersions)
        } finally {
            rawModuleInfoCache.clear()
        }

    /**
     * An internally used data class with information about a module retrieved from the module's package.json. This
     * information is further processed and eventually converted to an [ModuleInfo] object containing everything
     * required by the Yarn package manager.
     */
    private data class RawModuleInfo(
        val name: String,
        val version: String,
        val dependencyNames: Set<String>,
        val packageJson: File
    )

    // TODO: Add support for bundledDependencies.
    private fun resolveDependenciesInternal(
        analysisRoot: File,
        definitionFile: File,
        excludes: Excludes,
        allowDynamicVersions: Boolean
    ): List<ProjectAnalyzerResult> {
        val workingDir = definitionFile.parentFile
        installDependencies(analysisRoot, workingDir, allowDynamicVersions)

        val projectDirs = findWorkspaceSubmodules(workingDir) + definitionFile.parentFile

        return projectDirs.map { projectDir ->
            val issues = mutableListOf<Issue>()

            val project = runCatching {
                val packageJsonFile = projectDir.resolve(NodePackageManagerType.DEFINITION_FILE)
                parseProject(packageJsonFile, analysisRoot)
            }.getOrElse {
                issues += createAndLogIssue("Failed to parse project information: ${it.collectMessages()}")

                Project.EMPTY
            }

            val scopeNames = setOfNotNull(
                // Optional dependencies are just like regular dependencies except that Yarn ignores failures when
                // installing them (see https://classic.yarnpkg.com/en/docs/package-json#toc-optionaldependencies), i.e.
                // they are not a separate scope in ORT semantics.
                buildDependencyGraphForScopes(
                    project,
                    projectDir,
                    setOf(DEPENDENCIES_SCOPE, OPTIONAL_DEPENDENCIES_SCOPE),
                    DEPENDENCIES_SCOPE,
                    projectDirs,
                    workspaceDir = workingDir,
                    excludes = excludes
                ),

                buildDependencyGraphForScopes(
                    project,
                    projectDir,
                    setOf(DEV_DEPENDENCIES_SCOPE),
                    DEV_DEPENDENCIES_SCOPE,
                    projectDirs,
                    workspaceDir = workingDir,
                    excludes = excludes
                )
            )

            ProjectAnalyzerResult(
                project = project.copy(scopeNames = scopeNames),
                // Packages are set later by createPackageManagerResult().
                packages = emptySet(),
                issues = issues
            )
        }
    }

    private fun getModuleInfo(
        moduleDir: File,
        scopes: Set<String>,
        projectDirs: Set<File>,
        ancestorModuleDirs: List<File> = emptyList(),
        ancestorModuleIds: List<Identifier> = emptyList()
    ): ModuleInfo? {
        val moduleInfo = parsePackageJson(moduleDir, scopes)
        val dependencies = mutableSetOf<ModuleInfo>()

        val isProject = moduleDir.realFile() in projectDirs
        val packageType = if (isProject) projectType else "NPM"

        val moduleId = splitNamespaceAndName(moduleInfo.name).let { (namespace, name) ->
            Identifier(packageType, namespace, name, moduleInfo.version)
        }

        val cycleStartIndex = ancestorModuleIds.indexOf(moduleId)
        if (cycleStartIndex >= 0) {
            val cycle = (ancestorModuleIds.subList(cycleStartIndex, ancestorModuleIds.size) + moduleId)
                .joinToString(" -> ")
            logger.debug { "Not adding dependency '$moduleId' to avoid cycle: $cycle." }
            return null
        }

        val pathToRoot = listOf(moduleDir) + ancestorModuleDirs
        moduleInfo.dependencyNames.forEach { dependencyName ->
            val dependencyModuleDirPath = findDependencyModuleDir(dependencyName, pathToRoot)

            if (dependencyModuleDirPath.isNotEmpty()) {
                val dependencyModuleDir = dependencyModuleDirPath.first()

                getModuleInfo(
                    moduleDir = dependencyModuleDir,
                    scopes = setOf(DEPENDENCIES_SCOPE, OPTIONAL_DEPENDENCIES_SCOPE),
                    projectDirs,
                    ancestorModuleDirs = dependencyModuleDirPath.subList(1, dependencyModuleDirPath.size),
                    ancestorModuleIds = ancestorModuleIds + moduleId
                )?.also { dependencies += it }

                return@forEach
            }

            logger.debug {
                "It seems that the '$dependencyName' module was not installed as the package file could not be found " +
                    "anywhere in '${pathToRoot.joinToString()}'. This might be fine if the module is specific to a " +
                    "platform other than the one ORT is running on. A typical example is the 'fsevents' module."
            }
        }

        return ModuleInfo(
            id = moduleId,
            workingDir = moduleDir,
            packageFile = moduleInfo.packageJson,
            dependencies = dependencies,
            isProject = isProject
        )
    }

    /**
     * Retrieve all the dependencies of [project] from the given [scopes] and add them to the dependency graph under
     * the given [targetScope]. Return the target scope name if dependencies are found; *null* otherwise.
     */
    private fun buildDependencyGraphForScopes(
        project: Project,
        workingDir: File,
        scopes: Set<String>,
        targetScope: String,
        projectDirs: Set<File>,
        workspaceDir: File? = null,
        excludes: Excludes
    ): String? {
        if (excludes.isScopeExcluded(targetScope)) return null

        val moduleInfo = checkNotNull(getModuleInfo(workingDir, scopes, projectDirs, listOfNotNull(workspaceDir)))

        graphBuilder.addDependencies(project.id, targetScope, moduleInfo.dependencies)

        return targetScope.takeUnless { moduleInfo.dependencies.isEmpty() }
    }

    private fun parsePackageJson(moduleDir: File, scopes: Set<String>): RawModuleInfo =
        rawModuleInfoCache.getOrPut(moduleDir to scopes) {
            val packageJsonFile = moduleDir.resolve(NodePackageManagerType.DEFINITION_FILE)
            logger.debug { "Parsing module info from '${packageJsonFile.absolutePath}'." }
            val json = packageJsonFile.readTree()

            val name = json["name"].textValueOrEmpty()
            if (name.isBlank()) {
                logger.warn {
                    "The '$packageJsonFile' does not set a name, which is only allowed for unpublished packages."
                }
            }

            val version = json["version"].textValueOrEmpty()
            if (version.isBlank()) {
                logger.warn {
                    "The '$packageJsonFile' does not set a version, which is only allowed for unpublished packages."
                }
            }

            val dependencyNames = scopes.flatMapTo(mutableSetOf()) { scope ->
                // Yarn ignores "//" keys in the dependencies to allow comments, therefore ignore them here as well.
                json[scope].fieldNamesOrEmpty().asSequence().filterNot { it == "//" }
            }

            RawModuleInfo(
                name = name,
                version = version,
                dependencyNames = dependencyNames,
                packageJson = packageJsonFile
            )
        }

    /**
     * Find the directories which are defined as submodules of the project within [moduleDir].
     */
    private fun findWorkspaceSubmodules(moduleDir: File): Set<File> =
        submodulesCache.getOrPut(moduleDir.absolutePath) {
            loadWorkspaceSubmodules(moduleDir)
        }

    private fun installDependencies(analysisRoot: File, workingDir: File, allowDynamicVersions: Boolean) {
        requireLockfile(analysisRoot, workingDir, allowDynamicVersions) { managerType.hasLockfile(workingDir) }

        YarnCommand.run(workingDir, "install", "--ignore-scripts", "--ignore-engines", "--immutable").requireSuccess()
    }

    internal fun getRemotePackageDetails(packageName: String): PackageJson? {
        yarnInfoCache.read(packageName)?.also { return parsePackageJson(it) }

        val process = YarnCommand.run("info", "--json", packageName).requireSuccess()

        return parseYarnInfo(process.stdout, process.stderr)?.also {
            yarnInfoCache.write(packageName, Json.encodeToString(it))
        }
    }
}

private val logger = loggerOf(MethodHandles.lookup().lookupClass())

/**
 * Parse the given [stdout] of a Yarn _info_ command to a [PackageJson]. The output is typically a JSON object with the
 * metadata of the package that was queried. However, under certain circumstances, Yarn may return multiple JSON objects
 * separated by newlines; for instance, if the operation is retried due to network problems. This function filters for
 * the object with the data based on the _type_ field. Result is *null* if no matching object is found or the input is
 * not valid JSON.
 *
 * Note: The mentioned network issue can be reproduced by setting the network timeout to be very short via the command
 * line option '--network-timeout'.
 */
internal fun parseYarnInfo(stdout: String, stderr: String): PackageJson? =
    extractDataNodes(stdout, "inspect").firstOrNull()?.let(::parsePackageJson).alsoIfNull {
        extractDataNodes(stderr, "warning").forEach {
            logger.info { "Warning running Yarn info: ${it.jsonPrimitive.content}" }
        }

        extractDataNodes(stderr, "error").forEach {
            logger.warn { "Error parsing Yarn info: ${it.jsonPrimitive.content}" }
        }
    }

private fun extractDataNodes(output: String, type: String): Set<JsonElement> =
    runCatching {
        output.byteInputStream().use { inputStream ->
            Json.decodeToSequence<JsonObject>(inputStream)
                .filter { (it["type"] as? JsonPrimitive)?.content == type }
                .mapNotNullTo(mutableSetOf()) { it["data"] }
        }
    }.getOrDefault(emptySet())

private fun findDependencyModuleDir(dependencyName: String, searchModuleDirs: List<File>): List<File> {
    searchModuleDirs.forEachIndexed { index, moduleDir ->
        // Note: resolve() also works for scoped dependencies, e.g. dependencyName = "@x/y"
        val dependencyModuleDir = moduleDir.resolve("node_modules/$dependencyName")
        if (dependencyModuleDir.isDirectory) {
            return listOf(dependencyModuleDir) + searchModuleDirs.subList(index, searchModuleDirs.size)
        }
    }

    return emptyList()
}
