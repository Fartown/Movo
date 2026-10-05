package io.github.fartown.movo.hook.system

import io.github.fartown.movo.config.PowerAssistantTarget
import io.github.fartown.movo.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBindingTest {
    @Test
    fun `OEM target has no managed assistant binding`() {
        assertNull(assistantBindingFor(PowerAssistantTarget.OEM))
        assertFalse(
            shouldConfigureAssistant(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.OEM,
            ),
        )
    }

    @Test
    fun `Gemini and Movo use their own packages and components`() {
        val gemini = requireNotNull(assistantBindingFor(PowerAssistantTarget.GEMINI))
        val movo = requireNotNull(assistantBindingFor(PowerAssistantTarget.MOVO))

        assertEquals(ModuleConfig.GOOGLE_PACKAGE, gemini.packageName)
        assertEquals(ModuleConfig.GOOGLE_ASSISTANT_COMPONENT, gemini.componentName)
        assertEquals(ModuleConfig.MOVO_PACKAGE, movo.packageName)
        assertEquals(ModuleConfig.MOVO_VOICE_INTERACTION_COMPONENT, movo.componentName)
    }

    @Test
    fun `automatic configuration requires enabled switch and unchanged target`() {
        assertTrue(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.GEMINI,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = false,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.GEMINI,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.MOVO,
            ),
        )
    }

    @Test
    fun `preference changes configure managed targets and restore OEM`() {
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.GEMINI),
        )
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.MOVO),
        )
        assertEquals(
            AssistantSelectionAction.NONE,
            assistantSelectionAction(false, PowerAssistantTarget.GEMINI),
        )
        assertEquals(
            AssistantSelectionAction.RESTORE_OEM,
            assistantSelectionAction(false, PowerAssistantTarget.OEM),
        )
    }
}
