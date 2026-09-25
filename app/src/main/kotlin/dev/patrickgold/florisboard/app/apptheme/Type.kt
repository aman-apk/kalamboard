/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.apptheme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R

/**
 * Almarai — the shared typeface of the Aman app family (owner decision, 2026-08-13).
 *
 * Almarai ships **Regular (400) and Bold (700) only**. There is no Medium/SemiBold file, so every
 * style below pins its weight to exactly one of those two: a request for W500 would otherwise be
 * resolved by Compose's font matcher down to Regular and silently drop the intended emphasis.
 *
 * Scope: this family is for the **settings / app UI** only. The keyboard surface renders its key
 * labels through the Snygg theme engine, which is insulated from this typography — `FlorisImeTheme`
 * wraps the IME in a bare `MaterialTheme {}` and resets `LocalTextStyle` to `TextStyle.Default`.
 * Key glyph rendering stays on the system font by design; do not wire Almarai into it.
 */
val Almarai = FontFamily(
    Font(R.font.almarai_regular, FontWeight.Normal),
    Font(R.font.almarai_bold, FontWeight.Bold),
)

/**
 * Swaps the family in and pins the weight, keeping every metric of the receiver (font size, line
 * height, letter spacing, font feature settings) exactly as Material 3 defined it. Nothing about
 * digit rendering or spacing changes — only the typeface and the weight. The 1sp optical size
 * correction (see below) is applied separately, as a chained `.copy` at each slot definition.
 */
private fun TextStyle.almarai(weight: FontWeight): TextStyle = this.copy(
    fontFamily = Almarai,
    fontWeight = weight,
)

/** Untouched Material 3 baseline, read purely for its per-style metrics. */
private val M3 = MaterialTypography()

/**
 * Weight mapping (Material 3 default -> Almarai):
 * - W400 styles -> Regular, unchanged.
 * - `titleMedium`, `titleSmall`, `labelLarge` were W500 -> **Bold**. These carry deliberate
 *   emphasis (section headings, list-row titles, button labels) that Regular would flatten.
 * - `labelMedium` (12sp) and `labelSmall` (11sp) were W500 -> **Regular**. Almarai Bold is heavy
 *   and smudges at those sizes; Regular is the better of the two available weights there.
 *
 * Optical size correction (owner order, 2026-08-13): Almarai renders optically larger/heavier than
 * the previous typeface at equal sp, so every slot the settings UI actually renders is pinned 1sp
 * below its Material 3 default (floor 11sp), with line height at 1.5x the new size on a 0.5sp
 * grid. `titleLarge` counts as rendered — the M3 `TopAppBar` styles every screen title with it.
 * Slots nothing in the app renders (`display*`, `headlineLarge`, `labelMedium`) keep pure M3
 * metrics, and `labelSmall` already sits at the 11sp floor, so all four stay size-untouched.
 */
val Typography = MaterialTypography(
    displayLarge = M3.displayLarge.almarai(FontWeight.Normal),
    displayMedium = M3.displayMedium.almarai(FontWeight.Normal),
    displaySmall = M3.displaySmall.almarai(FontWeight.Normal),

    headlineLarge = M3.headlineLarge.almarai(FontWeight.Normal),
    headlineMedium = M3.headlineMedium.almarai(FontWeight.Normal)
        .copy(fontSize = 27.sp, lineHeight = 40.5.sp),
    headlineSmall = M3.headlineSmall.almarai(FontWeight.Normal)
        .copy(fontSize = 23.sp, lineHeight = 34.5.sp),

    titleLarge = M3.titleLarge.almarai(FontWeight.Normal)
        .copy(fontSize = 21.sp, lineHeight = 31.5.sp),
    titleMedium = M3.titleMedium.almarai(FontWeight.Bold)
        .copy(fontSize = 15.sp, lineHeight = 22.5.sp),
    titleSmall = M3.titleSmall.almarai(FontWeight.Bold)
        .copy(fontSize = 13.sp, lineHeight = 19.5.sp),

    // bodyLarge keeps the fork's own override shape (plain size, no explicit line height or
    // letter spacing) instead of the Material 3 baseline; its 16sp is dropped to 15sp by the
    // same 1sp Almarai correction as the other slots.
    bodyLarge = TextStyle(
        fontFamily = Almarai,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
    ),
    bodyMedium = M3.bodyMedium.almarai(FontWeight.Normal)
        .copy(fontSize = 13.sp, lineHeight = 19.5.sp),
    bodySmall = M3.bodySmall.almarai(FontWeight.Normal)
        .copy(fontSize = 11.sp, lineHeight = 16.5.sp),

    labelLarge = M3.labelLarge.almarai(FontWeight.Bold)
        .copy(fontSize = 13.sp, lineHeight = 19.5.sp),
    labelMedium = M3.labelMedium.almarai(FontWeight.Normal),
    labelSmall = M3.labelSmall.almarai(FontWeight.Normal),
)
