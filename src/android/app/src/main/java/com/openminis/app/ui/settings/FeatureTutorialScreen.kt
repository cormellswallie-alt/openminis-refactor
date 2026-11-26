package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.MaterialShapes
import com.openminis.app.ui.theme.toShape

/**
 * Interactive searchable Feature Guide & Tutorial screen launched from the
 * bottom of Settings. Explains every major Minis capability in detail with
 * instant bilingual keyword/command search and category filtering.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FeatureTutorialScreen(
    onBack: () -> Unit,
) {
    val isChinese = remember { FeatureTutorialCatalog.isChineseLocale() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(FeatureTutorialCatalog.Category.ALL) }
    var expandedIds by remember { mutableStateOf(setOf<String>()) }

    val filteredItems = remember(searchQuery, selectedCategory) {
        FeatureTutorialCatalog.filter(query = searchQuery, category = selectedCategory)
    }
    val hasQuery = searchQuery.isNotBlank()

    SettingsScaffold(
        title = stringResource(R.string.tutorial_screen_title),
        onBack = onBack,
        scrollable = false,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Pinned Search Bar ───────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.tutorial_search_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.common_clear),
                            )
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp, bottom = 8.dp),
            )

            // ── Category Filter Chips ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (category in FeatureTutorialCatalog.Category.entries) {
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = category.label(isChinese),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            // Live result summary bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tutorial_result_count, filteredItems.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (filteredItems.isNotEmpty() && !hasQuery) {
                    val allExpanded = filteredItems.all { it.id in expandedIds }
                    MinisTextButton(
                        onClick = {
                            expandedIds = if (allExpanded) emptySet()
                            else filteredItems.mapTo(HashSet()) { it.id }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (allExpanded) R.string.tutorial_collapse_all else R.string.tutorial_expand_all,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ExpressiveEmptyStateCard(
                            icon = Icons.Outlined.MenuBook,
                            title = stringResource(R.string.tutorial_no_results_title),
                            subtitle = stringResource(R.string.tutorial_no_results_subtitle),
                            badgeShape = MaterialShapes.Sunny.toShape(),
                        )
                        Spacer(Modifier.height(12.dp))
                        MinisTextButton(
                            onClick = {
                                searchQuery = ""
                                selectedCategory = FeatureTutorialCatalog.Category.ALL
                            },
                        ) {
                            Text(stringResource(R.string.tutorial_reset_filters))
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        // Auto-expand matched cards while actively searching so users locate answers immediately.
                        val isExpanded = hasQuery || item.id in expandedIds
                        TutorialFeatureCard(
                            item = item,
                            isChinese = isChinese,
                            expanded = isExpanded,
                            onToggle = {
                                expandedIds = if (item.id in expandedIds) expandedIds - item.id
                                else expandedIds + item.id
                            },
                        )
                    }
                    item(key = "bottom_spacer") {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TutorialFeatureCard(
    item: FeatureTutorialCatalog.TutorialItem,
    isChinese: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val (icon, accent) = categoryIconAndColor(item.category)
    val (badgeBg, badgeFg) = expressiveIconBadgeColors(accent)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(badgeBg, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeFg,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.category.label(isChinese),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = item.title(isChinese),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = item.summary(isChinese),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item.triggers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (trigger in item.triggers) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        ) {
                            Text(
                                text = trigger,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    )
                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.tutorial_section_what),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.details(isChinese),
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.tutorial_section_how),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    item.steps(isChinese).forEachIndexed { idx, step ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "${idx + 1}. ",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accent,
                            )
                            Text(
                                text = step,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = item.tip(isChinese),
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun categoryIconAndColor(category: FeatureTutorialCatalog.Category): Pair<ImageVector, Color> = when (category) {
    FeatureTutorialCatalog.Category.WORKFLOW -> Icons.Outlined.AutoAwesome to MaterialTheme.colorScheme.primary
    FeatureTutorialCatalog.Category.TOOLS -> Icons.Outlined.Build to Color(0xFF0097A7)
    FeatureTutorialCatalog.Category.MEMORY_AGENTS -> Icons.Outlined.Psychology to MaterialTheme.colorScheme.secondary
    FeatureTutorialCatalog.Category.MODELS -> Icons.Outlined.Hub to Color(0xFF1F9D55)
    FeatureTutorialCatalog.Category.STORAGE -> Icons.Outlined.Inventory2 to Color(0xFFD97706)
    FeatureTutorialCatalog.Category.SANDBOX -> Icons.Outlined.Terminal to MaterialTheme.colorScheme.tertiary
    FeatureTutorialCatalog.Category.ALL -> Icons.Outlined.MenuBook to MaterialTheme.colorScheme.primary
}
