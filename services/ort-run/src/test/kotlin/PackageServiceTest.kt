/*
 * Copyright (C) 2024 The ORT Server Authors (See <https://github.com/eclipse-apoapsis/ort-server/blob/main/NOTICE>)
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

package org.eclipse.apoapsis.ortserver.services.ortrun

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.containExactly
import io.kotest.matchers.collections.containExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.containExactly as containExactlyEntries
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

import io.mockk.mockk

import org.eclipse.apoapsis.ortserver.dao.test.DatabaseTestExtension
import org.eclipse.apoapsis.ortserver.dao.test.Fixtures
import org.eclipse.apoapsis.ortserver.model.AnalyzerJobConfiguration
import org.eclipse.apoapsis.ortserver.model.JobConfigurations
import org.eclipse.apoapsis.ortserver.model.OrtRun
import org.eclipse.apoapsis.ortserver.model.resolvedconfiguration.PackageCurationProviderConfig
import org.eclipse.apoapsis.ortserver.model.resolvedconfiguration.ResolvedPackageCurations
import org.eclipse.apoapsis.ortserver.model.runs.Identifier
import org.eclipse.apoapsis.ortserver.model.runs.Package
import org.eclipse.apoapsis.ortserver.model.runs.PackageFilters
import org.eclipse.apoapsis.ortserver.model.runs.ProcessedDeclaredLicense
import org.eclipse.apoapsis.ortserver.model.runs.Project
import org.eclipse.apoapsis.ortserver.model.runs.RemoteArtifact
import org.eclipse.apoapsis.ortserver.model.runs.ShortestDependencyPath
import org.eclipse.apoapsis.ortserver.model.runs.repository.PackageCuration
import org.eclipse.apoapsis.ortserver.model.runs.repository.PackageCurationData
import org.eclipse.apoapsis.ortserver.model.util.ComparisonOperator
import org.eclipse.apoapsis.ortserver.model.util.FilterOperatorAndValue
import org.eclipse.apoapsis.ortserver.model.util.ListQueryParameters
import org.eclipse.apoapsis.ortserver.model.util.OrderDirection
import org.eclipse.apoapsis.ortserver.model.util.OrderField

import org.jetbrains.exposed.sql.Database

class PackageServiceTest : WordSpec() {
    private val dbExtension = extension(DatabaseTestExtension())

    private lateinit var db: Database
    private lateinit var fixtures: Fixtures
    private lateinit var service: PackageService

    init {
        beforeEach {
            db = dbExtension.db
            fixtures = dbExtension.fixtures

            val ortRunService = OrtRunService(
                db,
                fixtures.advisorJobRepository,
                fixtures.advisorRunRepository,
                fixtures.analyzerJobRepository,
                fixtures.analyzerRunRepository,
                fixtures.evaluatorJobRepository,
                fixtures.evaluatorRunRepository,
                fixtures.ortRunRepository,
                fixtures.reporterJobRepository,
                fixtures.reporterRunRepository,
                fixtures.notifierJobRepository,
                fixtures.notifierRunRepository,
                fixtures.repositoryConfigurationRepository,
                fixtures.repositoryRepository,
                fixtures.resolvedConfigurationRepository,
                fixtures.scannerJobRepository,
                fixtures.scannerRunRepository,
                mockk(),
                mockk()
            )

            service = PackageService(db, ortRunService)
        }

        "listForOrtRunId" should {
            "return the packages for the given ORT run id" {
                val pkg1 = fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0"))

                val pkg2 = fixtures.generatePackage(
                    identifier = Identifier("Maven", "com.example", "example2", "1.0"),
                    description = "Another example package",
                    homepageUrl = "https://example2.com",
                    binaryArtifact = RemoteArtifact(
                        "https://example.com/example2-1.0.jar",
                        "0123456789abcdef0123456789abcdef01234567",
                        "SHA-1"
                    ),
                    sourceArtifact = RemoteArtifact(
                        "https://example.com/example2-1.0-sources.jar",
                        "0123456789abcdef0123456789abcdef01234567",
                        "SHA-1"
                    )
                )

                val ortRunId = createAnalyzerRunWithPackages(setOf(pkg1, pkg2)).id

                service.listForOrtRunId(ortRunId).data.map { it.pkg } should containExactlyInAnyOrder(pkg1, pkg2)
            }

            "return non-empty maps and sets for authors, declared licenses, and mapped and unmapped licenses" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(
                            identifier = Identifier("Maven", "com.example", "example", "1.0"),
                            authors = setOf("Author One", "Author Two", "Author Three"),
                            declaredLicenses = setOf("License 1", "License 2", "License 3", "License 4"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                spdxExpression = "LicenseRef-license",
                                mappedLicenses = mapOf(
                                    "License 1" to "LicenseRef-mapped-1",
                                    "License 2" to "LicenseRef-mapped-2",
                                ),
                                unmappedLicenses = setOf("License 3", "License 4")
                            ),
                        )
                    )
                ).id

                val results = service.listForOrtRunId(ortRunId).data

                results shouldHaveSize 1

                with(results.first().pkg) {
                    authors shouldHaveSize 3
                    authors shouldBe setOf("Author One", "Author Two", "Author Three")
                    declaredLicenses shouldHaveSize 4
                    declaredLicenses shouldBe setOf("License 1", "License 2", "License 3", "License 4")

                    with(processedDeclaredLicense) {
                        spdxExpression shouldBe "LicenseRef-license"
                        mappedLicenses shouldBe mapOf(
                            "License 1" to "LicenseRef-mapped-1",
                            "License 2" to "LicenseRef-mapped-2",
                        )
                        unmappedLicenses shouldHaveSize 2
                        unmappedLicenses shouldBe setOf("License 3", "License 4")
                    }
                }
            }

            "limit and sort the result based on query options" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example3", "1.0"))
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("purl", OrderDirection.DESCENDING)), limit = 2)
                )

                results.data shouldHaveSize 2
                results.totalCount shouldBe 3

                results.data.first().pkg.identifier.name shouldBe "example3"
                results.data.last().pkg.identifier.name shouldBe "example2"
            }

            "allow sorting by identifier" {
                val identifier1 = Identifier("NPM", "", "which", "2.0.2")
                val identifier2 = Identifier("Maven", "com.fasterxml.jackson.core", "jackson-databind", "2.9.6")
                val identifier3 = Identifier("Maven", "org.apache.logging.log4j", "log4j-core", "2.14.0")
                val identifier4 = Identifier("Maven", "com.fasterxml.jackson.core", "jackson-annotations", "2.17.1")
                val identifier5 = Identifier("Maven", "org.apache.logging.log4j", "log4j-core", "2.5")

                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(identifier1),
                        fixtures.generatePackage(identifier2),
                        fixtures.generatePackage(identifier3),
                        fixtures.generatePackage(identifier4),
                        fixtures.generatePackage(identifier5)
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("identifier", OrderDirection.DESCENDING)))
                )

                results.data shouldHaveSize 5
                results.totalCount shouldBe 5

                results.data.map { it.pkg.identifier } shouldContainInOrder listOf(
                    identifier1,
                    identifier5,
                    identifier3,
                    identifier2,
                    identifier4
                )
            }

            "allow sorting by processed declared license" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "MIT",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example2", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "Apache-2.0",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example3", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "EPL-1.0 OR LGPL-2.1-or-later",
                                emptyMap(),
                                emptySet()
                            )
                        )
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("processedDeclaredLicense", OrderDirection.ASCENDING)))
                )

                results.data shouldHaveSize 3
                results.totalCount shouldBe 3

                results.data[0].pkg.processedDeclaredLicense.spdxExpression shouldBe "Apache-2.0"
                results.data[1].pkg.processedDeclaredLicense.spdxExpression shouldBe "EPL-1.0 OR LGPL-2.1-or-later"
                results.data[2].pkg.processedDeclaredLicense.spdxExpression shouldBe "MIT"
            }

            "return an empty list if no packages were found in an ORT run" {
                val ortRun = createAnalyzerRunWithPackages(emptySet())

                val results = service.listForOrtRunId(ortRun.id).data

                results should beEmpty()
            }

            "return the shortest dependency paths for packages" {
                val project1 = fixtures.getProject()
                val project2 = fixtures.getProject(Identifier("Gradle", "", "project2", "1.0"))

                val identifier1 = Identifier("Maven", "com.example", "example", "1.0")
                val identifier2 = Identifier("Maven", "com.example", "example2", "1.0")

                val ortRunId = createAnalyzerRunWithPackages(
                    projects = setOf(project1, project2),
                    packages = setOf(
                        fixtures.generatePackage(identifier1),
                        fixtures.generatePackage(identifier2)
                    ),
                    shortestPaths = mapOf(
                        identifier1 to listOf(
                            ShortestDependencyPath(
                                project1.identifier,
                                "compileClassPath",
                                emptyList()
                            ),
                            ShortestDependencyPath(
                                project2.identifier,
                                "compileClassPath",
                                emptyList()
                            )
                        ),
                        identifier2 to listOf(
                            ShortestDependencyPath(
                                project1.identifier,
                                "compileClassPath",
                                listOf(identifier1)
                            )
                        )
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("purl", OrderDirection.DESCENDING)))
                )

                results.data shouldHaveSize 2

                with(results.data.first()) {
                    pkg.identifier shouldBe identifier2
                    shortestDependencyPaths shouldBe listOf(
                        ShortestDependencyPath(
                            project1.identifier,
                            "compileClassPath",
                            listOf(identifier1)
                        )
                    )
                }

                with(results.data.last()) {
                    pkg.identifier shouldBe identifier1
                    shortestDependencyPaths shouldBe listOf(
                        ShortestDependencyPath(
                            project1.identifier,
                            "compileClassPath",
                            emptyList()
                        ),
                        ShortestDependencyPath(
                            project2.identifier,
                            "compileClassPath",
                            emptyList()
                        )
                    )
                }
            }

            "allow filtering by identifier" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0"))
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("identifier", OrderDirection.DESCENDING))),
                    PackageFilters(
                        identifier = FilterOperatorAndValue(
                            ComparisonOperator.ILIKE,
                            "com.example/example2"
                        )
                    )
                )

                results.data shouldHaveSize 2
                results.totalCount shouldBe 2

                results.data.first().pkg.identifier shouldBe Identifier("NPM", "com.example", "example2", "1.0")
                results.data.last().pkg.identifier shouldBe Identifier("Maven", "com.example", "example2", "1.0")
            }

            "have a match when filtering by an identifier that doesn't have a namespace" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("NPM", "", "example", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example", "1.0"))
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    filters = PackageFilters(
                        identifier = FilterOperatorAndValue(
                            ComparisonOperator.ILIKE,
                            "NPM:example@1.0"
                        )
                    )
                )

                results.data shouldHaveSize 1
                results.data.first().pkg.identifier shouldBe Identifier("NPM", "", "example", "1.0")
            }

            "use case insensitive filtering for purl and identifier filtering" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0"))
                    )
                ).id

                val results1 = service.listForOrtRunId(
                    ortRunId,
                    filters = PackageFilters(
                        identifier = FilterOperatorAndValue(
                            ComparisonOperator.ILIKE,
                            "maven:com.example/Example2"
                        )
                    )
                )

                val results2 = service.listForOrtRunId(
                    ortRunId,
                    filters = PackageFilters(
                        purl = FilterOperatorAndValue(
                            ComparisonOperator.ILIKE,
                            "pkg:maven/com.example/Example2"
                        )
                    )
                )

                results1.data shouldHaveSize 1
                results1.totalCount shouldBe 1

                results1.data.first().pkg.identifier shouldBe Identifier("Maven", "com.example", "example2", "1.0")

                results2 shouldBe results1
            }

            "allow filtering by purl" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0"))
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("identifier", OrderDirection.DESCENDING))),
                    PackageFilters(
                        purl = FilterOperatorAndValue(
                            ComparisonOperator.ILIKE,
                            "pkg:NPM"
                        )
                    )
                )

                results.data shouldHaveSize 1
                results.totalCount shouldBe 1

                results.data.first().pkg.purl shouldBe "pkg:NPM/com.example/example2@1.0"
            }

            "allow filtering by processed declared license" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "Apache-2.0 OR LGPL-2.1-or-later",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example2", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "Apache-2.0",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("NPM", "com.example", "example2", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "MIT",
                                emptyMap(),
                                emptySet()
                            )
                        )
                    )
                ).id

                val results = service.listForOrtRunId(
                    ortRunId,
                    ListQueryParameters(listOf(OrderField("processedDeclaredLicense", OrderDirection.ASCENDING))),
                    PackageFilters(
                        processedDeclaredLicense = FilterOperatorAndValue(
                            ComparisonOperator.IN,
                            setOf("MIT", "Apache-2.0 OR LGPL-2.1-or-later")
                        )
                    )
                )

                results.data shouldHaveSize 2
                results.totalCount shouldBe 2

                with(results.data.first().pkg) {
                    identifier shouldBe Identifier("Maven", "com.example", "example", "1.0")
                    processedDeclaredLicense.spdxExpression shouldBe "Apache-2.0 OR LGPL-2.1-or-later"
                }

                with(results.data.last().pkg) {
                    identifier shouldBe Identifier("NPM", "com.example", "example2", "1.0")
                    processedDeclaredLicense.spdxExpression shouldBe "MIT"
                }
            }

            "apply curations and include applied curations" {
                val pkg1 = fixtures.generatePackage(
                    Identifier("Maven", "com.example", "example1", "1.0")
                )

                val pkg2 = fixtures.generatePackage(
                    Identifier("Maven", "com.example", "example2", "1.0"),
                    declaredLicenses = setOf("LicenseRef-declared1", "invalid-license")
                )

                val ortRunId = createAnalyzerRunWithPackages(setOf(pkg1, pkg2)).id

                val curation1 = PackageCuration(
                    id = pkg1.identifier,
                    data = PackageCurationData(
                        comment = "comment1",
                        authors = setOf("author1", "author2"),
                        concludedLicense = "LicenseRef-concluded1"
                    )
                )

                val curation2 = PackageCuration(
                    id = pkg2.identifier,
                    data = PackageCurationData(
                        comment = "comment2",
                        declaredLicenseMapping = mapOf("invalid-license" to "LicenseRef-mapped"),
                        concludedLicense = "LicenseRef-concluded2"
                    )
                )

                val resolvedPackageCurations = ResolvedPackageCurations(
                    provider = PackageCurationProviderConfig("test"),
                    curations = listOf(curation1, curation2)
                )

                fixtures.resolvedConfigurationRepository.addPackageCurations(ortRunId, listOf(resolvedPackageCurations))

                val packages = service.listForOrtRunId(ortRunId)
                packages.data shouldHaveSize 2

                with(packages.data.single { it.pkg.identifier == pkg1.identifier }) {
                    pkg.authors should containExactly(*curation1.data.authors.orEmpty().toTypedArray())
                    concludedLicense shouldBe curation1.data.concludedLicense
                    curations shouldHaveSize 1
                    curations.first() shouldBe curation1.data
                }

                with(packages.data.single { it.pkg.identifier == pkg2.identifier }) {
                    pkg.processedDeclaredLicense.spdxExpression shouldBe "LicenseRef-declared1 AND LicenseRef-mapped"
                    pkg.processedDeclaredLicense.mappedLicenses should
                            containExactlyEntries("invalid-license" to "LicenseRef-mapped")
                    concludedLicense shouldBe curation2.data.concludedLicense
                    curations shouldHaveSize 1
                    curations.first() shouldBe curation2.data
                }
            }
        }

        "countForOrtRunId" should {
            "return count for packages found in an ORT run" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0"))
                    )
                ).id

                service.countForOrtRunIds(ortRunId) shouldBe 2
            }

            "return count for packages found in ORT runs" {
                val repositoryId = fixtures.createRepository().id

                val pkg1 = fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0"))
                val pkg2 = fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0"))
                val pkg3 = fixtures.generatePackage(Identifier("Maven", "com.example", "example3", "1.0"))

                val ortRun1Id = createAnalyzerRunWithPackages(setOf(pkg1, pkg2), repositoryId).id
                val ortRun2Id = createAnalyzerRunWithPackages(setOf(pkg1, pkg3), repositoryId).id

                service.countForOrtRunIds(ortRun1Id, ortRun2Id) shouldBe 3
            }
        }

        "countEcosystemsForOrtRunIds" should {
            "list package types and counts for packages found in an ORT run" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example3", "1.0"))
                    )
                ).id

                val ecosystems = service.countEcosystemsForOrtRunIds(ortRunId)

                ecosystems shouldHaveSize 2
                ecosystems.first().name shouldBe "Maven"
                ecosystems.first().count shouldBe 2
                ecosystems.last().name shouldBe "NPM"
                ecosystems.last().count shouldBe 1
            }

            "list package types and counts for packages found in ORT runs" {
                val repositoryId = fixtures.createRepository().id

                val ortRun1Id = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example3", "1.0"))
                    ),
                    repositoryId
                ).id

                val ortRun2Id = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example4", "1.0")),
                        fixtures.generatePackage(Identifier("NPM", "com.example", "example2", "1.0")),
                        fixtures.generatePackage(Identifier("Maven", "com.example", "example3", "1.0"))
                    ),
                    repositoryId
                ).id

                service.countForOrtRunIds(ortRun1Id, ortRun2Id) shouldBe 4

                val ecosystems = service.countEcosystemsForOrtRunIds(ortRun1Id, ortRun2Id)
                ecosystems shouldHaveSize 2
                ecosystems.first().name shouldBe "Maven"
                ecosystems.first().count shouldBe 3
                ecosystems.last().name shouldBe "NPM"
                ecosystems.last().count shouldBe 1
            }
        }

        "getProcessedDeclaredLicenses" should {
            "return the distinct processed declared SPDX licenses found in packages in the ORT run" {
                val ortRunId = createAnalyzerRunWithPackages(
                    setOf(
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "Apache-2.0 OR LGPL-2.1-or-later",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("Maven", "com.example", "example2", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "Apache-2.0",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("NPM", "com.example", "example3", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "MIT",
                                emptyMap(),
                                emptySet()
                            )
                        ),
                        fixtures.generatePackage(
                            Identifier("NPM", "com.example", "example4", "1.0"),
                            processedDeclaredLicense = ProcessedDeclaredLicense(
                                "MIT",
                                emptyMap(),
                                emptySet()
                            )
                        )
                    )
                ).id

                val licenses = service.getProcessedDeclaredLicenses(ortRunId)

                licenses.size shouldBe 3
                licenses shouldBe listOf("Apache-2.0", "Apache-2.0 OR LGPL-2.1-or-later", "MIT")
            }
        }
    }

    private fun createAnalyzerRunWithPackages(
        packages: Set<Package>,
        repositoryId: Long = fixtures.createRepository().id,
        projects: Set<Project> = emptySet(),
        shortestPaths: Map<Identifier, List<ShortestDependencyPath>> = emptyMap()
    ): OrtRun {
        val ortRun = fixtures.createOrtRun(
            repositoryId = repositoryId,
            revision = "revision",
            jobConfigurations = JobConfigurations()
        )

        val analyzerJob = fixtures.createAnalyzerJob(
            ortRunId = ortRun.id,
            configuration = AnalyzerJobConfiguration(),
        )

        fixtures.createAnalyzerRun(
            analyzerJobId = analyzerJob.id,
            projects = projects,
            packages = packages,
            shortestDependencyPaths = shortestPaths
        )

        return ortRun
    }
}
