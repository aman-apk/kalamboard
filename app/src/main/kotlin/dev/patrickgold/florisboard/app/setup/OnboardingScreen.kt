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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/** KalamBoard's champagne-gold brand accent, used sparingly for the onboarding highlights. */
private val KalamGold = Color(0xFFD4AF37)

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
                                .background(Color.Black),
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
                                .background(KalamGold.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = page.icon,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = KalamGold,
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
                        if (selected) KalamGold else MaterialTheme.colorScheme.outlineVariant,
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
                    .padding(bottom = 28.dp)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KalamGold,
                    contentColor = Color(0xFF191203),
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
        }
    }
}
