package com.trytobatter.app.feature.plans

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trytobatter.app.core.designsystem.*
import com.trytobatter.app.core.model.*

@Composable fun CategoryPlanScreen(category: Category, state: AppSnapshot, onBack: () -> Unit, onTask: (String) -> Unit, onAdd: () -> Unit, onRecipes: () -> Unit = {}) {
    val tasks = state.tasks.filter { it.category == category }.sortedBy { it.start }
    DetailScaffold(category.label, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Space.Page, 8.dp, Space.Page, 16.dp), verticalArrangement = Arrangement.spacedBy(Space.Gap)) {
            item { Text("${tasks.size} 项安排 · ${tasks.count { it.isFinished }} 项已记录结果",style=MaterialTheme.typography.bodyMedium) }
            if(category == Category.MEAL) item { AppCard {
                Text("双人菜谱",style=MaterialTheme.typography.titleMedium)
                Text("查看已交接菜单的食材份量与做法。手机和电脑需连接同一局域网。",style=MaterialTheme.typography.bodySmall,color=BatterColors.Secondary)
                Button(onClick=onRecipes,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("打开双人菜谱")}
            } }
            item { SectionTitle("具体安排", "添加事项", onAdd) }
            if(tasks.isEmpty()) item { EmptyState("这类计划还没有安排", "添加一个可执行的小步骤，再按实际情况记录。", action = "添加事项", onAction = onAdd) }
            items(tasks, key = { it.id }) { t -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(t.day.toString(), style = MaterialTheme.typography.bodySmall, color = BatterColors.Secondary); TaskRow(t, TaskRules.conflicts(t, state.tasks).isNotEmpty()) { onTask(t.id) } } }
        }
    }
}
