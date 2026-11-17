package com.kronokeep.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kronokeep.app.ui.theme.MetalColors
import kotlin.math.cos
import kotlin.math.sin

/*
 * Shared building blocks for direction C, "Asset Tag" (design/previews/c-asset-tag.html).
 * Teal (primary) is for everyday actions and selection; brass (secondary) only marks things of record.
 * Material defaults that would paint selection brass (tonal buttons, chips, segmented buttons, nav indicator)
 * get teal colours from [KronoDefaults] instead.
 */

/** Screen header: a heavy title with an optional one-line caption under it ("Synced 2 minutes ago"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KronoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    )
}

/** Small teal section label with an optional leading icon and trailing slot (a count, a link). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** The default content card: flat, on the lighter surface, medium (16 dp) corners. */
@Composable
fun KronoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, color = color, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth(), content = inner)
    } else {
        Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth(), content = inner)
    }
}

/** An icon sitting in a soft circle, for list rows and empty states. */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** Pill tabs (Schedule · History · Documents): the selected one is filled teal, the rest are plain text. */
@Composable
fun PillTabs(
    titles: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        titles.forEachIndexed { index, title ->
            val selected = index == selectedIndex
            val container by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "tab-container",
            )
            val content by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tab-content",
            )
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(container)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

object KronoDefaults {
    /** "Update odometer", "Snooze": the teal wash, not Material's brass-tinted tonal default. */
    @Composable
    fun tonalButtonColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    /** Brass: export, share and anything else that produces a record. */
    @Composable
    fun recordButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    )

    @Composable
    fun filterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    @Composable
    fun segmentedButtonColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primary,
        activeContentColor = MaterialTheme.colorScheme.onPrimary,
        activeBorderColor = MaterialTheme.colorScheme.primary,
        inactiveContainerColor = Color.Transparent,
        inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )
}

/* ---- Asset-tag metal, shared by the asset list, the detail header and the sign-in mark ---- */

/** Brushed-metal face: four stops along a ~115 degree diagonal, like the CSS preview. */
fun brushedMetal(stops: List<Color>): Brush {
    val angle = Math.toRadians(115.0 - 90.0)
    val dx = cos(angle).toFloat()
    val dy = sin(angle).toFloat()
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val half = (size.width * dx + size.height * dy) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            return LinearGradientShader(
                from = Offset(c.x - dx * half, c.y - dy * half),
                to = Offset(c.x + dx * half, c.y + dy * half),
                colors = stops,
                colorStops = listOf(0f, 0.38f, 0.55f, 1f),
            )
        }
    }
}

/** One rivet, lit from the top left. */
fun DrawScope.drawRivet(metal: MetalColors, center: Offset, radius: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to metal.rivetHighlight,
            0.2f to metal.rivetHighlight,
            0.6f to metal.rivetMid,
            1f to metal.rivetShadow,
            center = Offset(center.x - radius * 0.3f, center.y - radius * 0.3f),
            radius = radius * 1.3f,
        ),
        radius = radius,
        center = center,
    )
}

/** Two rivets down the left edge of a tag. Content should leave ~40 dp of start padding for them. */
fun Modifier.leftRivets(metal: MetalColors, rivetRadius: Dp = 6.dp, edgeInset: Dp = 16.dp): Modifier = drawBehind {
    val r = rivetRadius.toPx()
    val x = 14.dp.toPx() + r
    val inset = edgeInset.toPx() + r
    listOf(inset, size.height - inset).forEach { y -> drawRivet(metal, Offset(x, y), r) }
}
