/*
 * Copyright (C) 2026 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.app.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.remember
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.jetpref.datastore.model.collectAsState
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/** KalamBoard's amber-gold brand accent, used sparingly for the onboarding highlights. */
private val KalamGold = Color(0xFFE0A32E)

/**
 * Deeper amber from the same family, used instead of [KalamGold] for accents drawn directly
 * on light surfaces (icon tints, pager dots), where the brighter gold falls below WCAG 3:1.
 */
private val KalamGoldDeep = Color(0xFFA87616)

/** Dark ink used for text sitting on the gold accent. */
private val KalamInk = Color(0xFF191203)

@Composable
private fun LanguagePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        if (selected) KalamGold else MaterialTheme.colorScheme.surfaceVariant,
        label = "languagePillColor",
    )
    Button(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = if (selected) KalamInk else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class OnboardingPage(
    val icon: ImageVector?, // null = show the app icon instead
    val titleRes: Int,
    val bodyRes: Int,
)

/**
 * One-time feature introduction shown on first launch, before the IME setup wizard.
 * Five short slides, marketing-toned but concise; skippable at any point.
 */
@Composable
fun OnboardingScreen() {
    val navController = LocalNavController.current
    val prefs by FlorisPreferenceStore
    val scope = rememberCoroutineScope()

    val pages = listOf(
        OnboardingPage(null, R.string.onboarding__welcome__title, R.string.onboarding__welcome__body),
        OnboardingPage(Icons.Default.Lock, R.string.onboarding__privacy__title, R.string.onboarding__privacy__body),
        OnboardingPage(Icons.Default.AutoAwesome, R.string.onboarding__arabic__title, R.string.onboarding__arabic__body),
        OnboardingPage(Icons.Default.ContentPaste, R.string.onboarding__clipboard__title, R.string.onboarding__clipboard__body),
        OnboardingPage(Icons.Default.Palette, R.string.onboarding__themes__title, R.string.onboarding__themes__body),
    )
    val pagerState = rememberPagerState { pages.size }
    val isLastPage = pagerState.currentPage == pages.size - 1

    // Gold accents sitting directly on the surface need the deeper family shade in light theme
    // to stay >= 3:1; filled gold containers keep KalamGold with KalamInk text (8.4:1).
    val surfaceAccent = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        KalamGold
    } else {
        KalamGoldDeep
    }

    fun finishOnboarding() {
        scope.launch { prefs.internal.onboardingCompleted.set(true) }
        navController.navigate(Routes.Setup.Screen) {
            popUpTo(0) { inclusive = true }
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { finishOnboarding() }) {
                    Text(stringRes(R.string.onboarding__action_skip))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { pageIndex ->
                val page = pages[pageIndex]
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (page.icon == null) {
                        Box(
                            modifier = Modifier
                                .size(132.dp)
                                .clip(CircleShape)
                                // Adopted Aman family "skies" identity: the ivory glyph floats on the
                                // golden-sky diagonal gradient with the dawn wash («صحوة الضوء»),
                                // mirroring ic_app_icon_background.xml (108-viewport geometry scaled).
                                .drawBehind {
                                    drawRect(
                                        Brush.linearGradient(
                                            0.0f to Color(0xFFD9A93E),
                                            0.5f to Color(0xFFAE7E23),
                                            1.0f to Color(0xFF6F4E12),
                                            start = Offset(size.width * (20f / 108f), 0f),
                                            end = Offset(size.width * (88f / 108f), size.height),
                                        )
                                    )
                                    drawRect(
                                        Brush.radialGradient(
                                            0.0f to Color(0x4DF7E8C2),
                                            0.45f to Color(0x24F7E8C2),
                                            0.85f to Color(0x0DF7E8C2),
                                            1.0f to Color(0x00F7E8C2),
                                            center = Offset(size.width * (30f / 108f), size.height * (20f / 108f)),
                                            radius = size.width * (115f / 108f),
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_app_icon_foreground),
                                contentDescription = null,
                                modifier = Modifier.size(150.dp),
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .clip(CircleShape)
                                .background(surfaceAccent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = surfaceAccent,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(36.dp))
                    Text(
                        text = stringRes(page.titleRes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringRes(page.bodyRes),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pageIndex == 0) {
                        // Language choice for the settings app AND this onboarding itself:
                        // setting the pref rebuilds the localized resources context, so the
                        // whole UI (text + RTL direction) switches live.
                        Spacer(modifier = Modifier.height(30.dp))
                        Text(
                            text = stringRes(R.string.onboarding__language__label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val settingsLanguage by prefs.other.settingsLanguage.collectAsState()
                        val resolvedLanguage = remember(settingsLanguage) {
                            if (settingsLanguage == "auto") {
                                FlorisLocale.default().language
                            } else {
                                FlorisLocale.fromTag(settingsLanguage).language
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LanguagePill(
                                label = "العربية",
                                selected = resolvedLanguage == "ar",
                                onClick = { scope.launch { prefs.other.settingsLanguage.set("ar") } },
                            )
                            LanguagePill(
                                label = "English",
                                selected = resolvedLanguage == "en",
                                onClick = { scope.launch { prefs.other.settingsLanguage.set("en") } },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pages.size) { index ->
                    val selected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dotWidth")
                    val dotColor by animateColorAsState(
                        if (selected) surfaceAccent else MaterialTheme.colorScheme.outlineVariant,
                        label = "dotColor",
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                }
            }

            Button(
                onClick = {
                    if (isLastPage) {
                        finishOnboarding()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KalamGold,
                    contentColor = KalamInk,
                ),
            ) {
                Text(
                    text = stringRes(
                        if (isLastPage) R.string.onboarding__action_get_started
                        else R.string.onboarding__action_next
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // توقيع «مختبرات أمان» — خاتم العائلة في ذيل صفحة الترحيب.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.amanlabs_rosette),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringRes(R.string.aman_labs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
