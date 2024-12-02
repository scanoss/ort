package org.ossreviewtoolkit.plugins.scanners.scanoss

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.should
import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.ossreviewtoolkit.model.LicenseFinding
import org.ossreviewtoolkit.model.PackageType
import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.Snippet
import org.ossreviewtoolkit.model.SnippetFinding
import org.ossreviewtoolkit.model.TextLocation
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.SnippetChoices
import org.ossreviewtoolkit.model.config.snippet.Choice
import org.ossreviewtoolkit.model.config.snippet.Given
import org.ossreviewtoolkit.model.config.snippet.Provenance
import org.ossreviewtoolkit.model.config.snippet.SnippetChoice
import org.ossreviewtoolkit.model.config.snippet.SnippetChoiceReason
import org.ossreviewtoolkit.scanner.ScanContext
import org.ossreviewtoolkit.scanner.ScannerWrapperConfig
import org.ossreviewtoolkit.utils.spdx.SpdxExpression
import java.io.File
import java.util.UUID

/**
 * A test for scanning a directory with the [ScanOss] scanner.
 */


private val TEST_DIRECTORY_TO_SCAN = File("src/test/assets/filesToScan")


class ScanOssScannerSnippetChoiceTest : StringSpec({
    lateinit var scanner: ScanOss

    val server = WireMockServer(
        WireMockConfiguration.options()
            .dynamicPort()
            .usingFilesUnderDirectory("src/test/assets/scanMulti")
    )

    beforeSpec {
        server.start()
        val config = ScanOssConfig(apiUrl = "http://localhost:${server.port()}", apiKey = "")
        scanner = spyk(ScanOss.Factory().create(config, ScannerWrapperConfig.Companion.EMPTY))
    }

    afterSpec {
        server.stop()
    }

    beforeTest {
        server.resetAll()
    }


    "Test SnippetChoices" {
        // Manipulate the UUID generation to have the same IDs as in the response.
        every {
            scanner.generateRandomUUID()
        } answers {
            UUID.fromString("5530105e-0752-4750-9c07-4e4604b879a5")
        } andThenAnswer {
            UUID.fromString("c198b884-f6cf-496f-95eb-0e7968dd2ec6")
        }

        //TODO: What would be the expected output when a file has multiple choices for different line ranges
        val snippetChoices = listOf(
            SnippetChoices(
                provenance = Provenance("https://google.com"), // mandatory TODO: This is ignored by SCANOSS
                choices = listOf(
                    SnippetChoice(
                        given = Given(
                            sourceLocation = TextLocation(
                                path = TEST_DIRECTORY_TO_SCAN.resolve("ArchiveUtils.kt").toString(),
                                startLine = 1,  // mandatory TODO: This is ignored by SCANOSS (IGNORE)
                                endLine = 10    // mandatory TODO: This is ignored by SCANOSS
                            )
                        ),
                        choice = Choice(
                            purl = "pkg:github/scanoss/ort",
                            reason = SnippetChoiceReason.ORIGINAL_FINDING,
                            comment = "Optional comment"
                        )
                    )
                )
            )
        )

        val summary = scanner.scanPath(
            TEST_DIRECTORY_TO_SCAN,
            ScanContext(
                labels = emptyMap(),
                packageType = PackageType.PACKAGE,
                snippetChoices = snippetChoices
            )

        )

        verify(exactly = 1) {
            scanner.createWfpForFile(TEST_DIRECTORY_TO_SCAN.resolve("ArchiveUtils.kt"))
            scanner.createWfpForFile(TEST_DIRECTORY_TO_SCAN.resolve("ScannerFactory.kt"))
        }

        with(summary) {
            licenseFindings should containExactlyInAnyOrder(
                LicenseFinding(
                    license = "Apache-2.0",
                    location = TextLocation(
                        path = "scanner/src/main/kotlin/ScannerFactory.kt",
                        line = TextLocation.Companion.UNKNOWN_LINE
                    ),
                    score = 100.0f
                )
            )

            snippetFindings.shouldNotContain(
                SnippetFinding(
                    TextLocation("utils/src/main/kotlin/ArchiveUtils.kt", 1, 240),
                    setOf(
                        Snippet(
                            99.0f,
                            TextLocation(
                                "https://osskb.org/api/file_contents/871fb0c5188c2f620d9b997e225b0095",
                                128,
                                367
                            ),
                            RepositoryProvenance(
                                VcsInfo(VcsType.Companion.GIT, "https://github.com/scanoss/ort.git", ""), "."
                            ),
                            "pkg:github/scanoss/ort",
                            SpdxExpression.Companion.parse("Apache-2.0")
                        )
                    )
                )
            )
        }
    }
})
