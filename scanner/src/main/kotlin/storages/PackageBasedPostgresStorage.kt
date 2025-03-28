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

package org.ossreviewtoolkit.scanner.storages

import com.fasterxml.jackson.core.JsonProcessingException

import java.sql.SQLException

import javax.sql.DataSource

import org.apache.logging.log4j.kotlin.logger

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import org.jetbrains.exposed.sql.SchemaUtils.withDataBaseLock
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.utils.DatabaseUtils.checkDatabaseEncoding
import org.ossreviewtoolkit.model.utils.DatabaseUtils.tableExists
import org.ossreviewtoolkit.model.utils.DatabaseUtils.transaction
import org.ossreviewtoolkit.model.utils.arrayParam
import org.ossreviewtoolkit.model.utils.rawParam
import org.ossreviewtoolkit.model.utils.tilde
import org.ossreviewtoolkit.scanner.ScanStorageException
import org.ossreviewtoolkit.scanner.ScannerMatcher
import org.ossreviewtoolkit.scanner.storages.utils.ScanResultDao
import org.ossreviewtoolkit.scanner.storages.utils.ScanResults
import org.ossreviewtoolkit.utils.common.collectMessages
import org.ossreviewtoolkit.utils.ort.showStackTrace

private val TABLE_NAME = ScanResults.tableName

/**
 * The Postgres storage back-end.
 */
class PackageBasedPostgresStorage(
    /**
     * The JDBC data source to obtain database connections.
     */
    private val dataSource: Lazy<DataSource>
) : AbstractPackageBasedScanStorage() {
    companion object {
        /** Expression to reference the scanner version as an array. */
        private const val VERSION_ARRAY =
            "string_to_array(regexp_replace(scan_result->'scanner'->>'version', '[^0-9.]', '', 'g'), '.')"

        /** Expression to convert the scanner version to a numeric array for comparisons. */
        private const val VERSION_EXPRESSION = "$VERSION_ARRAY::int[]"
    }

    /** The [Database] instance on which all operations are executed. */
    private val database by lazy {
        Database.connect(dataSource.value, databaseConfig = DatabaseConfig { defaultFetchSize = 1000 }).apply {
            transaction {
                withDataBaseLock {
                    if (!tableExists(TABLE_NAME)) {
                        checkDatabaseEncoding()

                        @Suppress("DEPRECATION")
                        createMissingTablesAndColumns(ScanResults)

                        createIdentifierAndScannerVersionIndex()
                        createScanResultUniqueIndex()
                    }
                }
            }
        }
    }

    private fun Transaction.createIdentifierAndScannerVersionIndex() =
        exec(
            """
            CREATE INDEX identifier_and_scanner_version
                ON $TABLE_NAME USING btree
                (
                    identifier,
                    (scan_result->'scanner'->>'name'),
                    $VERSION_ARRAY
                )
                TABLESPACE pg_default
            """.trimIndent()
        )

    /**
     * Create an index that ensures that there is only one scan result for the same identifier, scanner, and provenance.
     */
    private fun Transaction.createScanResultUniqueIndex() =
        exec(
            """
            CREATE UNIQUE INDEX scan_result_unique_index
                ON $TABLE_NAME USING btree
                (
                    identifier,
                    (scan_result->'provenance'),
                    (scan_result->'scanner')
                )
                TABLESPACE pg_default
            """.trimIndent()
        )

    override fun readInternal(pkg: Package, scannerMatcher: ScannerMatcher?): Result<List<ScanResult>> {
        val minVersionArray = scannerMatcher?.minVersion?.run { intArrayOf(major, minor, patch) }
        val maxVersionArray = scannerMatcher?.maxVersion?.run { intArrayOf(major, minor, patch) }

        return runCatching {
            database.transaction {
                ScanResultDao.find {
                    var expression = ScanResults.identifier eq pkg.id

                    scannerMatcher?.regScannerName?.let {
                        expression = expression and (rawParam("scan_result->'scanner'->>'name'") tilde it)
                    }

                    minVersionArray?.let {
                        expression = expression and (rawParam(VERSION_EXPRESSION) greaterEq arrayParam(it))
                    }

                    maxVersionArray?.let {
                        expression = expression and (rawParam(VERSION_EXPRESSION) less arrayParam(it))
                    }

                    expression
                }.map { it.scanResult }
                    // TODO: Currently the query only accounts for the scanner criteria. Ideally also the provenance
                    //       should be checked in the query to reduce the downloaded data.
                    .filter { it.provenance.matches(pkg) }
            }
        }.onFailure {
            if (it is JsonProcessingException || it is SQLException) {
                it.showStackTrace()

                val message = "Could not read scan results for '${pkg.id.toCoordinates()}' with " +
                    "$scannerMatcher from database: ${it.collectMessages()}"

                logger.info { message }

                return Result.failure(ScanStorageException(message))
            }
        }
    }

    override fun addInternal(id: Identifier, scanResult: ScanResult): Result<Unit> {
        logger.info { "Storing scan result for '${id.toCoordinates()}' in storage." }

        // TODO: Check if there is already a matching entry for this provenance and scanner details.

        return runCatching {
            database.transaction {
                ScanResultDao.new {
                    identifier = id
                    this.scanResult = scanResult
                }
            }
        }.recoverCatching {
            it.showStackTrace()

            val message = "Could not store scan result for '${id.toCoordinates()}': ${it.collectMessages()}"

            logger.warn { message }

            throw ScanStorageException(message)
        }.map { /* Unit */ }
    }
}
