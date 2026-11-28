package com.example

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontSynthesis
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.AlertCoralContainer
import com.example.ui.theme.ClearPathTypography
import com.example.ui.theme.HighContrastSecondaryText
import com.example.ui.theme.HighContrastText
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.ReadyEmerald
import com.example.ui.theme.ReadyEmeraldContainer
import com.example.ui.theme.RestrainedCyan
import com.example.ui.theme.RestrainedCyanContainer
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.WarmAmberContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ClearPathAccessibilityTest {

    private val allTextStyles = listOf(
        ClearPathTypography.displayLarge,
        ClearPathTypography.displayMedium,
        ClearPathTypography.displaySmall,
        ClearPathTypography.headlineLarge,
        ClearPathTypography.headlineMedium,
        ClearPathTypography.headlineSmall,
        ClearPathTypography.titleLarge,
        ClearPathTypography.titleMedium,
        ClearPathTypography.titleSmall,
        ClearPathTypography.bodyLarge,
        ClearPathTypography.bodyMedium,
        ClearPathTypography.bodySmall,
        ClearPathTypography.labelLarge,
        ClearPathTypography.labelMedium,
        ClearPathTypography.labelSmall
    )

    @Test
    fun bodyTextMeetsWcagAaOnEverySurface() {
        val surfaces = listOf(
            ObsidianBackground,
            ObsidianSurface,
            ObsidianSurfaceElevated,
            ObsidianSurfaceHighest
        )
        listOf(HighContrastText, HighContrastSecondaryText).forEach { text ->
            surfaces.forEach { surface ->
                assertTrue(
                    "$text on $surface must meet 4.5:1",
                    contrastRatio(text, surface) >= 4.5f
                )
            }
        }
    }

    @Test
    fun accentTextMeetsWcagAaOnItsContainer() {
        assertTrue(contrastRatio(WarmAmber, ObsidianBackground) >= 4.5f)
        assertTrue(contrastRatio(WarmAmber, WarmAmberContainer) >= 4.5f)
        assertTrue(contrastRatio(RestrainedCyan, RestrainedCyanContainer) >= 4.5f)
        assertTrue(contrastRatio(ReadyEmerald, ReadyEmeraldContainer) >= 4.5f)
        assertTrue(contrastRatio(AlertCoral, AlertCoralContainer) >= 4.5f)
    }

    @Test
    fun outlineMeetsWcagNonTextContrast() {
        listOf(
            ObsidianBackground,
            ObsidianSurface,
            ObsidianSurfaceElevated,
            ObsidianSurfaceHighest
        ).forEach { surface ->
            assertTrue(
                "ObsidianBorder on $surface must meet 3:1",
                contrastRatio(ObsidianBorder, surface) >= 3f
            )
        }
    }

    @Test
    fun typographyUsesRealWeightFacesWithoutSynthesis() {
        allTextStyles.forEach { style ->
            assertEquals(FontSynthesis.None, style.fontSynthesis)
            val family = style.fontFamily as FontListFontFamily
            val weight = requireNotNull(style.fontWeight)
            assertTrue(
                "Missing $weight face in $family",
                family.fonts.any { it.weight == weight }
            )
        }
    }

    @Test
    fun typographyScalesWithUserFontScale() {
        allTextStyles.forEach { style ->
            assertTrue("Font size must use sp", style.fontSize.isSp)
            assertTrue("Line height must use sp", style.lineHeight.isSp)
        }
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val luminanceA = a.luminance()
        val luminanceB = b.luminance()
        return (max(luminanceA, luminanceB) + 0.05f) / (min(luminanceA, luminanceB) + 0.05f)
    }
}
