/*
 * Copyright (C) 2020 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.plugins.scanners.scanoss

import com.scanoss.Scanner
import com.scanoss.Winnowing
import com.scanoss.filters.FilterConfig
import com.scanoss.settings.*
import com.scanoss.utils.JsonUtils
import com.scanoss.utils.PackageDetails
import org.apache.logging.log4j.kotlin.logger
import org.apache.logging.log4j.kotlin.loggerOf
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.SnippetChoices
import org.ossreviewtoolkit.model.config.snippet.SnippetChoice
import org.ossreviewtoolkit.model.config.snippet.SnippetChoiceReason
import org.ossreviewtoolkit.scanner.*
import org.ossreviewtoolkit.utils.common.Options
import java.io.File
import java.lang.invoke.MethodHandles
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant



private val logger = loggerOf(MethodHandles.lookup().lookupClass())

class ScanOss internal constructor(
    override val name: String,
    config: ScanOssConfig,
    private val wrapperConfig: ScannerWrapperConfig,


) : PathScannerWrapper {
    class Factory : ScannerWrapperFactory<ScanOssConfig>("SCANOSS") {
        override fun create(config: ScanOssConfig, wrapperConfig: ScannerWrapperConfig) =
            ScanOss(type, config, wrapperConfig)

        override fun parseConfig(options: Options, secrets: Options) =
            ScanOssConfig.create(options, secrets).also { logger.info { "The $type API URL is ${it.apiUrl}." } }
    }

    private var config = config

    override val version: String by lazy {
        // TODO: Find out the best / cheapest way to query the SCANOSS server for its version.
        PackageDetails.getVersion()
    }

    override val configuration = ""

    override val matcher: ScannerMatcher? = null

    override val readFromStorage by lazy { wrapperConfig.readFromStorageWithDefault(matcher) }

    override val writeToStorage by lazy { wrapperConfig.writeToStorageWithDefault(matcher) }

    override fun scanPath(path: File, context: ScanContext): ScanSummary {
        val startTime = Instant.now()
        val rootPath = path.toPath()
        val filterConfig = FilterConfig.builder()
            .customFilter { p ->
                val isExcluded = context.excludes?.isPathExcluded(rootPath.relativize(p).toString()) ?: false
                logger.debug("Path: ${p}, isExcluded: $isExcluded")
                isExcluded
            }
            .build()

        val scanoss = Scanner.builder()
            .url(config.apiUrl.removeSuffix("/") + "/scan/direct")
            .apiKey(config.apiKey)
            .settings(buildSettingsFromORTContext(context, path.toPath()))
            .filterConfig(filterConfig)
            .build()

        val rawResults: List<String> = when {
            path.isFile -> listOf(scanoss.scanFile(path.absolutePath))
            else -> scanoss.scanFolder(path.absolutePath)
        }

        val results = JsonUtils.toScanFileResults(rawResults)
        val endTime = Instant.now()
        return generateSummary(startTime, endTime, results)
    }


    private data class ProcessedRules(
        val includeRules: List<Rule>,
        val ignoreRules: List<Rule>,
        val replaceRules: List<ReplaceRule>,
        val removeRules: List<RemoveRule>
    )


    private fun buildSettingsFromORTContext(context: ScanContext, rootPath: Path): ScanossSettings {
        val rules = processSnippetChoices(context.snippetChoices, rootPath)
        val bom = Bom.builder()
            .ignore(rules.ignoreRules)
            .include(rules.includeRules)
            .replace(rules.replaceRules)
            .remove(rules.removeRules)
            .build()
        return ScanossSettings.builder().bom(bom).build()
    }

    private fun processSnippetChoices(snippetChoices: List<SnippetChoices>, rootPath: Path): ProcessedRules {
        val includeRules = mutableListOf<Rule>()
        val ignoreRules = mutableListOf<Rule>()
        val replaceRules = mutableListOf<ReplaceRule>()
        val removeRules = mutableListOf<RemoveRule>()

        snippetChoices.forEach { snippetChoice ->
            snippetChoice.choices.forEach { choice ->
                when (choice.choice.reason) {
                    SnippetChoiceReason.ORIGINAL_FINDING -> {
                        processOriginalFinding(
                            choice = choice,
                            includeRules = includeRules,
                            replaceRules = replaceRules,
                            rootPath = rootPath,
                        )
                    }
                    SnippetChoiceReason.NO_RELEVANT_FINDING -> {
                        processNoRelevantFinding(
                            choice = choice,
                            removeRules = removeRules,
                            ignoreRules = ignoreRules,
                            rootPath = rootPath,
                        )
                    }
                    SnippetChoiceReason.OTHER -> {
                        processOtherReason(choice)
                    }
                }
            }
        }

        return ProcessedRules(includeRules, ignoreRules,replaceRules, removeRules)
    }

    private fun processOriginalFinding(
        choice: SnippetChoice,
        includeRules: MutableList<Rule>,
        replaceRules: MutableList<ReplaceRule>,
        rootPath: Path,
    ) {
        includeRules.add(
            Rule.builder()
                .purl(choice.choice.purl)
                .path(rootPath.resolve(Paths.get(choice.given.sourceLocation.path)).toString())
                .build()
        )
    }

    private fun processNoRelevantFinding(
        choice: SnippetChoice,
        removeRules: MutableList<RemoveRule>,
        ignoreRules: MutableList<Rule>,
        rootPath: Path,
    ) {
        val builder = RemoveRule.builder()

        builder.path(choice.given.sourceLocation.path)

        // Set line range only if both start and end lines are positive numbers
        // If either line is <= 0, no line range is set and the rule will apply to the entire file
        if (choice.given.sourceLocation.startLine > 0 && choice.given.sourceLocation.endLine > 0) {
            builder.startLine(choice.given.sourceLocation.startLine)
            builder.endLine(choice.given.sourceLocation.endLine)
        }

        val rule = builder.build()
        removeRules.add(rule)
    }

    private fun processOtherReason(snippetChoice: SnippetChoice) {
        logger.info {
            "Encountered OTHER reason for snippet choice in file ${snippetChoice.given.sourceLocation.path}"
        }
    }

}
