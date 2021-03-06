/*
 * Copyright (C) 2019-2021 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.ossreviewtoolkit.model.utils

import java.io.File
import java.io.IOException
import java.security.MessageDigest

import kotlin.io.path.createTempFile
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.utils.FileMatcher
import org.ossreviewtoolkit.utils.ORT_NAME
import org.ossreviewtoolkit.utils.collectMessagesAsString
import org.ossreviewtoolkit.utils.log
import org.ossreviewtoolkit.utils.ortDataDirectory
import org.ossreviewtoolkit.utils.packZip
import org.ossreviewtoolkit.utils.perf
import org.ossreviewtoolkit.utils.showStackTrace
import org.ossreviewtoolkit.utils.storage.FileStorage
import org.ossreviewtoolkit.utils.toHexString
import org.ossreviewtoolkit.utils.unpackZip

/**
 * A class to archive files matched by provided [patterns] in a ZIP file that is stored in a [FileStorage][storage].
 */
class FileArchiver(
    /**
     * A collection of globs to match the paths of files that shall be archived. For details about the glob pattern see
     * [java.nio.file.FileSystem.getPathMatcher].
     */
    patterns: Collection<String>,

    /**
     * The [FileStorage] to use for archiving files.
     */
    private val storage: FileStorage
) {
    companion object {
        val DEFAULT_ARCHIVE_DIR by lazy { ortDataDirectory.resolve("scanner/archive") }
    }

    private val matcher = FileMatcher(
        patterns = patterns,
        ignoreCase = true
    )

    /**
     * Return whether an archive corresponding to [provenance] exists.
     */
    fun hasArchive(provenance: Provenance): Boolean {
        if (provenance.sourceArtifact == null && provenance.vcsInfo == null) return false

        val archivePath = getArchivePath(provenance)
        return storage.exists(archivePath)
    }

    /**
     * Archive all files in [directory] matching any of the configured [patterns] in the [storage].
     */
    fun archive(directory: File, provenance: Provenance) {
        require(provenance.sourceArtifact != null || provenance.vcsInfo != null) {
            "Unable to create an archive for unknown provenance."
        }

        val zipFile = createTempFile(ORT_NAME, ".zip").toFile()

        val zipDuration = measureTime {
            directory.packZip(zipFile, overwrite = true) { file ->
                val relativePath = file.relativeTo(directory).invariantSeparatorsPath

                matcher.matches(relativePath).also { result ->
                    log.debug {
                        if (result) {
                            "Adding '$relativePath' to archive."
                        } else {
                            "Not adding '$relativePath' to archive."
                        }
                    }
                }
            }
        }

        log.perf { "Archived directory '${directory.invariantSeparatorsPath}' in ${zipDuration.inMilliseconds}ms." }

        val writeDuration = measureTime { storage.write(getArchivePath(provenance), zipFile.inputStream()) }

        log.perf {
            "Wrote archive of directory '${directory.invariantSeparatorsPath}' to storage in " +
                    "${writeDuration.inMilliseconds}ms."
        }

        zipFile.delete()
    }

    /**
     * Unarchive the archive corresponding to [provenance].
     */
    fun unarchive(directory: File, provenance: Provenance): Boolean {
        if (provenance.sourceArtifact == null && provenance.vcsInfo == null) return false

        val archivePath = getArchivePath(provenance)

        return try {
            val (input, readDuration) = measureTimedValue { storage.read(archivePath) }

            log.perf {
                "Read archive of directory '${directory.invariantSeparatorsPath}' from storage in " +
                        "${readDuration.inMilliseconds}ms."
            }

            val unzipDuration = measureTime { input.use { it.unpackZip(directory) } }

            log.perf {
                "Unarchived directory '${directory.invariantSeparatorsPath}' in ${unzipDuration.inMilliseconds}ms."
            }

            true
        } catch (e: IOException) {
            e.showStackTrace()

            log.error { "Could not unarchive from $archivePath: ${e.collectMessagesAsString()}" }

            false
        }
    }
}

private fun getArchivePath(provenance: Provenance): String =
    "${provenance.hash()}/archive.zip"

private val SHA1_DIGEST by lazy { MessageDigest.getInstance("SHA-1") }

/**
 * Calculate the SHA-1 hash of the storage key of this [Provenance] instance.
 */
private fun Provenance.hash(): String {
    val key = vcsInfo?.let {
        "${it.type}${it.url}${it.resolvedRevision}"
    } ?: sourceArtifact!!.let {
        "${it.url}${it.hash.value}"
    }

    return SHA1_DIGEST.digest(key.toByteArray()).toHexString()
}
