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


    "should replace package URL from scanoss/ort to scanoss/scanoss.java in ArchiveUtils file" {
        //TODO: Complete test
    }
})
