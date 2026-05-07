package com.becalm.android.unit.e2e

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class E2eScenarioCatalogSpecTest {

    @Test
    fun `critical E2E scenario catalog keeps independent user-readable coverage`() {
        val scenarios = parseScenarios(featureFile())

        assertEquals(EXPECTED_SCENARIO_COUNT, scenarios.size)
        assertEquals(scenarios.size, scenarios.map { it.id }.distinct().size)

        scenarios.forEach { scenario ->
            assertTrue("${scenario.id} must use @happy or @error", scenario.tags.any { it == "@happy" || it == "@error" })
            assertTrue("${scenario.id} must not be both happy and error", !("@happy" in scenario.tags && "@error" in scenario.tags))
            assertTrue("${scenario.id} must have Given", "Given" in scenario.stepKeywords)
            assertTrue("${scenario.id} must have When", "When" in scenario.stepKeywords)
            assertTrue("${scenario.id} must have Then", "Then" in scenario.stepKeywords)
            assertTrue("${scenario.id} must declare Automation mapping", scenario.automation.isNotBlank())
            assertTrue("${scenario.id} automation cannot be TODO", !scenario.automation.contains("TODO", ignoreCase = true))
            assertTrue("${scenario.id} should declare isolated test data in Background or scenario", scenarioCatalogHasIsolation)
        }

        assertNotNull(scenarios.singleOrNull { it.id == "E2E-006" && "@routing" in it.tags })
        assertNotNull(scenarios.singleOrNull { it.id == "E2E-008" && "@regression" in it.tags })
        assertNotNull(scenarios.singleOrNull { it.id == "E2E-063" && "@memory" in it.tags })
        REQUIRED_DOMAIN_TAGS.forEach { tag ->
            assertTrue("Catalog must cover domain tag $tag", scenarios.any { tag in it.tags })
        }
        assertTrue(scenarios.count { "@error" in it.tags } >= MIN_ERROR_PATH_COUNT)
        assertTrue(scenarios.count { "@happy" in it.tags } >= MIN_HAPPY_PATH_COUNT)
    }

    private val scenarioCatalogHasIsolation: Boolean
        get() = featureFile().readText()
            .contains("each scenario starts with isolated local app data", ignoreCase = true)

    private fun featureFile(): File {
        val start = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val candidate = generateSequence(start) { it.parentFile }
            .map { File(it, "docs/e2e-scenarios/critical-user-scenarios.feature") }
            .firstOrNull { it.isFile }
        return requireNotNull(candidate) {
            "Could not find docs/e2e-scenarios/critical-user-scenarios.feature from $start"
        }
    }

    private fun parseScenarios(file: File): List<ParsedScenario> {
        val scenarios = mutableListOf<ParsedScenario>()
        var pendingTags = emptyList<String>()
        var current: MutableScenario? = null

        file.readLines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("@") -> pendingTags = line.split(Regex("\\s+")).filter { it.startsWith("@") }
                line.startsWith("Scenario:") -> {
                    current?.let { scenarios += it.toParsed() }
                    val name = line.removePrefix("Scenario:").trim()
                    val id = requireNotNull(SCENARIO_ID.find(name)?.value) {
                        "Scenario at line ${index + 1} must include E2E-### id"
                    }
                    current = MutableScenario(id = id, name = name, tags = pendingTags)
                    pendingTags = emptyList()
                }
                current != null && STEP_KEYWORDS.any { line.startsWith("$it ") } -> {
                    current.steps += line.substringBefore(" ")
                }
                current != null && line.startsWith("# Automation:") -> {
                    current.automation = line.removePrefix("# Automation:").trim()
                }
            }
        }
        current?.let { scenarios += it.toParsed() }
        return scenarios
    }

    private data class MutableScenario(
        val id: String,
        val name: String,
        val tags: List<String>,
        val steps: MutableList<String> = mutableListOf(),
        var automation: String = "",
    ) {
        fun toParsed(): ParsedScenario =
            ParsedScenario(
                id = id,
                name = name,
                tags = tags,
                stepKeywords = steps.toSet(),
                automation = automation,
            )
    }

    private data class ParsedScenario(
        val id: String,
        val name: String,
        val tags: List<String>,
        val stepKeywords: Set<String>,
        val automation: String,
    )

    private companion object {
        private const val EXPECTED_SCENARIO_COUNT = 72
        private const val MIN_HAPPY_PATH_COUNT = 50
        private const val MIN_ERROR_PATH_COUNT = 20
        private val REQUIRED_DOMAIN_TAGS = setOf(
            "@auth",
            "@onboarding",
            "@sources",
            "@sync",
            "@import",
            "@pipeline",
            "@people",
            "@commitments",
            "@today",
            "@notifications",
            "@memory",
            "@privacy",
            "@settings",
        )
        private val SCENARIO_ID = Regex("E2E-\\d{3}")
        private val STEP_KEYWORDS = listOf("Given", "When", "Then")
    }
}
