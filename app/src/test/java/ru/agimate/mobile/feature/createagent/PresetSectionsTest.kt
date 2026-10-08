package ru.agimate.mobile.feature.createagent

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.agimate.mobile.data.agents.AgentPresetDto
import ru.agimate.mobile.data.agents.TaxonomyItemDto

class PresetSectionsTest {

    private val categories = listOf(
        TaxonomyItemDto("PLATFORM", "Платформа"),
        TaxonomyItemDto("FINANCE", "Деньги и финансы"),
        TaxonomyItemDto("HOME", "Дом и быт"),
        TaxonomyItemDto("OTHER", "Прочее"),
    )

    private fun preset(name: String, category: String?) =
        AgentPresetDto(id = name, name = name, category = category)

    private fun List<PresetSection>.layout() = map { it.label to it.presets.map { p -> p.name } }

    @Test
    fun `sections follow the dictionary order and skip empty categories`() {
        val sections = presetSections(
            listOf(
                preset("assistant", "HOME"),
                preset("accountant", "FINANCE"),
                preset("advisor", "FINANCE"),
            ),
            categories,
        )

        assertEquals(
            listOf(
                "Деньги и финансы" to listOf("accountant", "advisor"),
                "Дом и быт" to listOf("assistant"),
            ),
            sections.layout(),
        )
    }

    @Test
    fun `a preset without a category lands in Other`() {
        val sections = presetSections(listOf(preset("legacy", null), preset("blank", "")), categories)

        assertEquals(listOf("Прочее" to listOf("legacy", "blank")), sections.layout())
    }

    @Test
    fun `a code the dictionary does not know goes last under its own code`() {
        val sections = presetSections(
            listOf(preset("crm", "SALES"), preset("assistant", "HOME")),
            categories,
        )

        assertEquals(
            listOf("Дом и быт" to listOf("assistant"), "SALES" to listOf("crm")),
            sections.layout(),
        )
    }

    @Test
    fun `without a dictionary the gallery is one list without headers`() {
        val presets = listOf(preset("assistant", "HOME"), preset("accountant", "FINANCE"))

        assertEquals(
            listOf(null to listOf("assistant", "accountant")),
            presetSections(presets, null).layout(),
        )
        assertEquals(
            listOf(null to listOf("assistant", "accountant")),
            presetSections(presets, emptyList()).layout(),
        )
    }
}
