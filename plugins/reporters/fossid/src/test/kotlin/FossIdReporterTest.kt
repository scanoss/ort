/*
 * Copyright (C) 2022 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.plugins.reporters.fossid

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldBeSingleton
import io.kotest.matchers.result.shouldBeSuccess

import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyAll
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk

import java.io.File

import org.ossreviewtoolkit.clients.fossid.FossIdRestService
import org.ossreviewtoolkit.clients.fossid.FossIdServiceWithVersion
import org.ossreviewtoolkit.clients.fossid.generateReport
import org.ossreviewtoolkit.clients.fossid.model.report.ReportType
import org.ossreviewtoolkit.clients.fossid.model.report.SelectionType
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Repository
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.ScannerDetails
import org.ossreviewtoolkit.model.UnknownProvenance
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
import org.ossreviewtoolkit.plugins.api.Secret
import org.ossreviewtoolkit.reporter.ReporterInput
import org.ossreviewtoolkit.utils.test.scannerRunOf

private const val SERVER_URL_SAMPLE = "https://fossid.example.com/instance/"
private const val API_KEY_SAMPLE = "XYZ"
private const val USER_KEY_SAMPLE = "user"

private val DEFAULT_CONFIG = FossIdReporterConfig(
    serverUrl = SERVER_URL_SAMPLE,
    apiKey = Secret(API_KEY_SAMPLE),
    user = Secret(USER_KEY_SAMPLE),
    reportType = "HTML_DYNAMIC",
    selectionType = "INCLUDE_ALL_LICENSES"
)

private const val SCANCODE_1 = "scancode1"
private const val SCANCODE_2 = "scancode2"
private val FILE_SAMPLE = File("fake_file")
private val DIRECTORY_SAMPLE = File("fake_directory")

class FossIdReporterTest : WordSpec({
    beforeSpec {
        mockkStatic("org.ossreviewtoolkit.clients.fossid.ExtensionsKt")
    }

    afterTest {
        clearAllMocks()
    }

    "generateReport of FossIdReport " should {
        "do nothing if no scancode is passed" {
            val (serviceMock, reporterMock) = createReporterMock()
            val input = createReporterInput()

            reporterMock.generateReport(input)

            coVerify(exactly = 0) {
                serviceMock.generateReport(any(), any(), any(), any(), any(), any())
            }
        }

        "use HTML_DYNAMIC as default report type" {
            val (serviceMock, reporterMock) = createReporterMock()
            val input = createReporterInput(SCANCODE_1)

            reporterMock.generateReport(input)

            coVerify(exactly = 1) {
                serviceMock.generateReport(
                    USER_KEY_SAMPLE,
                    API_KEY_SAMPLE,
                    SCANCODE_1,
                    ReportType.HTML_DYNAMIC,
                    any(),
                    any()
                )
            }
        }

        "allow to specify a report type" {
            val (serviceMock, reporterMock) = createReporterMock(DEFAULT_CONFIG.copy(reportType = "XLSX"))
            val input = createReporterInput(SCANCODE_1)

            reporterMock.generateReport(input)

            coVerify(exactly = 1) {
                serviceMock.generateReport(
                    USER_KEY_SAMPLE,
                    API_KEY_SAMPLE,
                    SCANCODE_1,
                    ReportType.XLSX,
                    any(),
                    any()
                )
            }
        }

        "use INCLUDE_ALL_LICENSES as default selection type" {
            val (serviceMock, reporterMock) = createReporterMock()
            val input = createReporterInput(SCANCODE_1)

            reporterMock.generateReport(input)

            coVerify(exactly = 1) {
                serviceMock.generateReport(
                    USER_KEY_SAMPLE,
                    API_KEY_SAMPLE,
                    SCANCODE_1,
                    any(),
                    SelectionType.INCLUDE_ALL_LICENSES,
                    any()
                )
            }
        }

        "allow to specify a selection type" {
            val (serviceMock, reporterMock) = createReporterMock(
                DEFAULT_CONFIG.copy(selectionType = "INCLUDE_COPYLEFT")
            )
            val input = createReporterInput(SCANCODE_1)

            reporterMock.generateReport(input)

            coVerify(exactly = 1) {
                serviceMock.generateReport(
                    USER_KEY_SAMPLE,
                    API_KEY_SAMPLE,
                    SCANCODE_1,
                    any(),
                    SelectionType.INCLUDE_COPYLEFT,
                    any()
                )
            }
        }

        "generate a report for each given scancode" {
            val (serviceMock, reporterMock) = createReporterMock()
            val input = createReporterInput(SCANCODE_1, SCANCODE_2)

            reporterMock.generateReport(input)

            coVerifyAll {
                serviceMock.generateReport(USER_KEY_SAMPLE, API_KEY_SAMPLE, SCANCODE_1, any(), any(), any())
                serviceMock.generateReport(USER_KEY_SAMPLE, API_KEY_SAMPLE, SCANCODE_2, any(), any(), any())
            }
        }

        "generate a report for merged scan results" {
            val (serviceMock, reporterMock) = createReporterMock()
            val input = createReporterInput("$SCANCODE_1,$SCANCODE_2")

            reporterMock.generateReport(input)

            coVerifyAll {
                serviceMock.generateReport(USER_KEY_SAMPLE, API_KEY_SAMPLE, SCANCODE_1, any(), any(), any())
                serviceMock.generateReport(USER_KEY_SAMPLE, API_KEY_SAMPLE, SCANCODE_2, any(), any(), any())
            }
        }

        "return the generated file(s)" {
            val (_, reporterMock) = createReporterMock()
            val input = createReporterInput(SCANCODE_1)

            val reportFileResults = reporterMock.generateReport(input)

            reportFileResults.shouldBeSingleton {
                it shouldBeSuccess FILE_SAMPLE
            }
        }

        "ignore scan results without a scancode" {
            val (serviceMock, reporterMock) = createReporterMock()
            val input = createReporterInput(SCANCODE_1)

            reporterMock.generateReport(input)

            coVerify(exactly = 1) {
                serviceMock.generateReport(any(), any(), any(), any(), any(), any())
            }
        }
    }
})

private fun FossIdReporter.generateReport(input: ReporterInput) = generateReport(input, DIRECTORY_SAMPLE)

private fun createReporterMock(config: FossIdReporterConfig = DEFAULT_CONFIG): Pair<FossIdRestService, FossIdReporter> {
    mockkObject(FossIdRestService)

    val serviceMock = mockk<FossIdServiceWithVersion>()
    val reporterMock = spyk(FossIdReporter(config = config))
    coEvery { FossIdRestService.create(any()) } returns serviceMock

    coEvery {
        serviceMock.generateReport(any(), any(), any(), any(), any(), any())
    } returns Result.success(FILE_SAMPLE)
    return serviceMock to reporterMock
}

private fun createReporterInput(vararg scanCodes: String): ReporterInput {
    val analyzedVcs = VcsInfo(
        type = VcsType.GIT,
        revision = "master",
        url = "https://github.com/path/first-project.git",
        path = "sub/path"
    )

    val results = scanCodes.associateByTo(
        destination = sortedMapOf(),
        keySelector = { Identifier.EMPTY.copy(name = it) },
        valueTransform = { code ->
            val unmatchedScanResult = ScanResult(
                provenance = UnknownProvenance,
                scanner = ScannerDetails.EMPTY.copy(name = "otherScanner"),
                summary = ScanSummary.EMPTY
            )
            listOf(createScanResult(code), unmatchedScanResult)
        }
    )

    return ReporterInput(
        OrtResult(
            repository = Repository(
                config = RepositoryConfiguration(
                    excludes = Excludes(
                        scopes = listOf(
                            ScopeExclude(
                                pattern = "test",
                                reason = ScopeExcludeReason.TEST_DEPENDENCY_OF,
                                comment = "Packages for testing only."
                            )
                        )
                    )
                ),
                vcs = analyzedVcs,
                vcsProcessed = analyzedVcs
            ),
            scanner = scannerRunOf(*results.toList().toTypedArray())
        )
    )
}

private fun createScanResult(scanCode: String): ScanResult =
    ScanResult(
        provenance = UnknownProvenance,
        scanner = ScannerDetails.EMPTY,
        summary = ScanSummary.EMPTY,
        additionalData = mapOf(FossIdReporter.SCAN_CODE_KEY to scanCode)
    )
