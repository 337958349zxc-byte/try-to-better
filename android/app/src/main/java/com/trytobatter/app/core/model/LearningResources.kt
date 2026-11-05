package com.trytobatter.app.core.model

import java.net.URI

data class LearningResource(val label: String, val url: String)

/** Read existing course metadata without changing task/backup/sync schemas. */
object LearningResources {
    private val links = Regex("""https?://[^\s<>"'，。；]+""")
    private fun valid(url: String): Boolean = runCatching {
        val uri = URI(url)
        url.length <= 4096 && url.none { it.code < 32 } && uri.scheme in listOf("http", "https") && uri.host != null && uri.rawUserInfo == null
    }.getOrDefault(false)

    fun from(task: TaskItem): List<LearningResource> {
        if(task.category !in setOf(Category.AI, Category.ENGLISH)) return emptyList()
        val labelled = linkedMapOf<String, String>()
        task.note.lineSequence().forEach { line -> links.findAll(line).forEach { match ->
            val url = match.value.trimEnd(')', ']', '}', '）', '】')
            if(valid(url)) labelled.putIfAbsent(url, line.substring(0, match.range.first).trim().trimEnd('：', ':').ifBlank { "补充资料" })
        } }
        val urls = (listOf(task.resourceUrl).filter(::valid) + labelled.keys).distinct()
        return urls.mapIndexed { index, url -> LearningResource(labelled[url] ?: if(index == 0) "主资料" else "补充资料", url) }
    }

    fun selections(note: String): List<String> {
        val lines = note.lines()
        val start = lines.indexOfFirst { it.trim() == "指定选集：" }
        if(start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { line ->
            listOf("精简内容：", "全部资源：", "前置条件：", "独立输出：", "后续课程").none { line.trim().startsWith(it) }
        }.map { it.trim() }.filter { it.isNotBlank() }.take(20)
    }
}
