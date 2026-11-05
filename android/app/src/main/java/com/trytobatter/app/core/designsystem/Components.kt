package com.trytobatter.app.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import com.trytobatter.app.core.model.*

fun Category.icon(): ImageVector = when(this) {
    Category.AI -> Icons.Outlined.Code
    Category.ENGLISH -> Icons.Outlined.Translate
    Category.MEAL -> Icons.Outlined.Restaurant
    Category.WORKOUT -> Icons.Outlined.FitnessCenter
    Category.LIFE -> Icons.Outlined.WorkOutline
}
fun Category.color(): Color = when(this) {
    Category.AI -> Color(0xFF3569EC)
    Category.ENGLISH -> Color(0xFF7759B2)
    Category.MEAL -> Color(0xFFA36513)
    Category.WORKOUT -> BatterColors.Success
    Category.LIFE -> Color(0xFF526B87)
}
fun Category.tint(): Color = when(this) {
    Category.AI -> BatterColors.LearningTint
    Category.ENGLISH -> BatterColors.LanguageTint
    Category.MEAL -> BatterColors.FoodTint
    Category.WORKOUT -> BatterColors.ExerciseTint
    Category.LIFE -> BatterColors.LifeTint
}
fun TaskStatus.color(): Color = when(this) {
    TaskStatus.RUNNING -> BatterColors.PrimaryDeep
    TaskStatus.COMPLETED -> BatterColors.Success
    TaskStatus.PARTIAL -> BatterColors.Warning
    else -> BatterColors.Secondary
}

@Composable fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val base = modifier.fillMaxWidth().clip(RoundedCornerShape(Space.Radius))
        .background(MaterialTheme.colorScheme.surface)
    Surface(modifier = if(onClick != null) base.clickable(onClick = onClick) else base,
        shape = RoundedCornerShape(Space.Radius), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(Space.Card), verticalArrangement = Arrangement.spacedBy(Space.Gap), content = content)
    }
}

@Composable fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if(action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable fun PageHeader(title: String, subtitle: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            if(subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        trailing?.invoke()
    }
}

@Composable fun CategoryIcon(category: Category, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(category.tint()), contentAlignment = Alignment.Center) {
        Icon(category.icon(), null, tint = category.color(), modifier = Modifier.size(22.dp))
    }
}

@Composable fun StateBadge(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    val resolved = if(color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color
    Surface(modifier, color = resolved.copy(alpha = .09f), shape = RoundedCornerShape(7.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = resolved)
    }
}

@Composable fun DemoNote(text: String = "计划与执行记录保存在本机") {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Info, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun Notice(text: String, action: String? = null, warning: Boolean = false, onClick: (() -> Unit)? = null) {
    val color = if(warning) Color(0xFF825000) else BatterColors.PrimaryDeep
    Surface(color = if(warning) Color(0xFFFFF3DE) else BatterColors.LightBlue, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if(warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(20.dp), tint = color)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
            if(action != null && onClick != null) TextButton(onClick = onClick) { Text(action, color = color) }
        }
    }
}

@Composable fun EmptyState(title: String, description: String, icon: ImageVector = Icons.Outlined.EventNote, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(BatterColors.LightBlue), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = BatterColors.Primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@Composable fun TaskRow(task: TaskItem, overlaps: Boolean = false, alarm: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    val timeWidth = 43.dp * LocalDensity.current.fontScale.coerceIn(1f,1.7f)
    val active = task.status == TaskStatus.RUNNING
    Surface(shape = RoundedCornerShape(Space.Radius), color = if(active) BatterColors.LightBlue else MaterialTheme.colorScheme.surface,
        border = BorderStroke(.5.dp, if(active) BatterColors.Primary.copy(alpha=.28f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.heightIn(min = Space.TaskMin).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(timeWidth)) {
                Text(task.start.format(ClockFormat), style = MaterialTheme.typography.bodyMedium)
                Text("${task.durationMinutes}分", style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary)
            }
            CategoryIcon(task.category, Modifier.size(30.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall, color = if(task.status == TaskStatus.COMPLETED) BatterColors.Secondary else MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                    if(task.status == TaskStatus.COMPLETED) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint=BatterColors.Success)
                    Text(listOfNotNull(task.status.label, "固定".takeIf { task.fixed }, "重复".takeIf { task.seriesId != null }, "时间重叠".takeIf { overlaps }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = if(overlaps) BatterColors.Warning else task.status.color())
                }
            }
            if(alarm != null) alarm() else Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = BatterColors.Secondary)
        }
    }
}

fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if(s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DetailScaffold(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleLarge) }, windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } }, actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }, content = content)
}
