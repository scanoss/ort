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
import com.scanoss.ScannerPostProcessor
import com.scanoss.Winnowing
import com.scanoss.dto.ScanFileResult
import com.scanoss.rest.ScanApi
import com.scanoss.settings.Bom
import com.scanoss.settings.Rule
import com.scanoss.settings.ReplaceRule
import com.scanoss.settings.RemoveRule
import com.scanoss.utils.JsonUtils
import com.scanoss.utils.PackageDetails

import java.io.File
import java.time.Instant
import java.util.UUID

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.config.SnippetChoices
import org.ossreviewtoolkit.model.config.snippet.SnippetChoice
import org.ossreviewtoolkit.model.config.snippet.SnippetChoiceReason
import org.ossreviewtoolkit.scanner.PathScannerWrapper
import org.ossreviewtoolkit.scanner.ScanContext
import org.ossreviewtoolkit.scanner.ScannerMatcher
import org.ossreviewtoolkit.scanner.ScannerWrapperConfig
import org.ossreviewtoolkit.scanner.ScannerWrapperFactory
import org.ossreviewtoolkit.utils.common.Options
import org.ossreviewtoolkit.utils.common.VCS_DIRECTORIES
import java.nio.file.Path
import java.nio.file.Paths

class ScanOss internal constructor(
    override val name: String,
    config: ScanOssConfig,
    private val wrapperConfig: ScannerWrapperConfig
) : PathScannerWrapper {
    class Factory : ScannerWrapperFactory<ScanOssConfig>("SCANOSS") {
        override fun create(config: ScanOssConfig, wrapperConfig: ScannerWrapperConfig) =
            ScanOss(type, config, wrapperConfig)

        override fun parseConfig(options: Options, secrets: Options) =
            ScanOssConfig.create(options, secrets).also { logger.info { "The $type API URL is ${it.apiUrl}." } }
    }


    private val service = ScanApi.builder()
        // As there is only a single endpoint, the SCANOSS API client expects the path to be part of the API URL.
        .url(config.apiUrl.removeSuffix("/") + "/scan/direct")
        .apiKey(config.apiKey)
        .build()


    override val version: String by lazy {
        // TODO: Find out the best / cheapest way to query the SCANOSS server for its version.
        PackageDetails.getVersion()
    }

    override val configuration = ""

    override val matcher by lazy { ScannerMatcher.create(details, wrapperConfig.matcherConfig) }

    override val readFromStorage by lazy { wrapperConfig.readFromStorageWithDefault(matcher) }

    override val writeToStorage by lazy { wrapperConfig.writeToStorageWithDefault(matcher) }

    /**
     * The name of the file corresponding to the fingerprints can be sent to SCANOSS for more precise matches.
     * However, for anonymity, a unique identifier should be generated and used instead. This property holds the
     * mapping between the file paths and the unique identifiers. When receiving the response, the UUID will be
     * replaced by the actual file path.
     *
     * TODO: This behavior should be driven by a configuration parameter enabled by default.
     */
    private val fileNamesAnonymizationMapping = mutableMapOf<UUID, String>()

    override fun scanPath(path: File, context: ScanContext): ScanSummary {
        val startTime = Instant.now()


        // Process context and build BOM
        val bom = processContextAndBuildBom(context, path.toPath())

        val wfpString = buildString {
            path.walk()
                .onEnter { it.name !in VCS_DIRECTORIES }
                .filterNot { it.isDirectory }
                .forEach {
                    logger.info { "Computing fingerprint for file ${it.absolutePath}..." }
                    append(createWfpForFile(it))
                }
        }

        val result = service.scan(
            wfpString,
            context.labels["scanOssContext"],
            context.labels["scanOssId"]?.toIntOrNull() ?: Thread.currentThread().threadId().toInt()
        )



        // Replace the anonymized UUIDs by their file paths.
        val results = JsonUtils.toScanFileResultsFromObject(JsonUtils.toJsonObject(result)).map {
            val uuid = UUID.fromString(it.filePath)

            val fileName = fileNamesAnonymizationMapping[uuid] ?: throw IllegalArgumentException(
                "The $name server returned UUID '$uuid' which is not present in the mapping."
            )

            ScanFileResult(fileName, it.fileDetails)
        }

        val scannerPostProcessor: ScannerPostProcessor = ScannerPostProcessor.builder().build()
        var postProcessedResults = scannerPostProcessor.process(results, bom);

        postProcessedResults.forEach { postProcessedResult ->
            println("** RESULT **")
            println(postProcessedResult.toString())
            println("** RESULT **")
        }

        val endTime = Instant.now()
        return generateSummary(startTime, endTime, postProcessedResults)
    }


    private data class ProcessedRules(
        val includeRules: List<Rule>,
        val ignoreRules: List<Rule>,
        val replaceRules: List<ReplaceRule>,
        val removeRules: List<RemoveRule>
    )

    private fun processContextAndBuildBom(context: ScanContext, rootPath: Path): Bom {
        val rules = processSnippetChoices(context.snippetChoices, rootPath)
        return buildBomFromRules(rules)
    }

    private fun processSnippetChoices(snippetChoices: List<SnippetChoices>, rootPath: Path): ProcessedRules {
        val includeRules = mutableListOf<Rule>()
        val ignoreRules = mutableListOf<Rule>()
        val replaceRules = mutableListOf<ReplaceRule>()
        val removeRules = mutableListOf<RemoveRule>()

        snippetChoices.forEach { snippetChoice ->
            snippetChoice.choices.forEach { choice ->
                when (choice.choice.reason) {
                    SnippetChoiceReason.ORIGINAL_FINDING -> {       //TODO: Choices can be made at snippet basis. How handle those cases?
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

        replaceRules.add(       //TODO: If a component actually contains {choice.choice.purl} it should not be replaced
                                // by the PostProcessorScanner
            ReplaceRule.builder()
                .path(rootPath.resolve(Paths.get(choice.given.sourceLocation.path)).toString())
                .replaceWith(choice.choice.purl)
                .build()
        )
    }

    private fun processNoRelevantFinding(
        choice: SnippetChoice,
        removeRules: MutableList<RemoveRule>,
        ignoreRules: MutableList<Rule>,
        rootPath: Path,
    ) {
        removeRules.add(
            RemoveRule.builder()
                .path(rootPath.resolve(Paths.get(choice.given.sourceLocation.path)).toString())
                .startLine(choice.given.sourceLocation.startLine)
                .endLine(choice.given.sourceLocation.endLine)
                .build()
        )
        ignoreRules.add(
            Rule.builder()
                .path(rootPath.resolve(Paths.get(choice.given.sourceLocation.path)).toString())
                .purl(choice.choice.purl)
                .build()
        )
    }

    private fun processOtherReason(snippetChoice: SnippetChoice) {
        logger.info {
            "Encountered OTHER reason for snippet choice in file ${snippetChoice.given.sourceLocation.path}"
        }
    }

    private fun buildBomFromRules(rules: ProcessedRules): Bom {
        return Bom.builder()
            .apply {
                rules.includeRules.forEach { include(it) }
                rules.replaceRules.forEach { replace(it) }
                rules.removeRules.forEach { remove(it) }
            }
            .build()
    }

    internal fun generateRandomUUID() = UUID.randomUUID()

    internal fun createWfpForFile(file: File): String {
        generateRandomUUID().let { uuid ->
            // TODO: Let's keep the original file extension to give SCANOSS some hint about the mime type.
            fileNamesAnonymizationMapping[uuid] = file.path
            return Winnowing.builder().build().wfpForFile(file.path, uuid.toString())
        }
    }
}
