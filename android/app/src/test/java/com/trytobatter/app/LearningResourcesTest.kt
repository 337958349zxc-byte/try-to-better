package com.trytobatter.app

import com.trytobatter.app.core.model.*
import org.junit.Assert.*
import org.junit.Test

class LearningResourcesTest {
    private fun course(url: String = "https://example.org/one", note: String = "") = TaskItem(title="听力诊断", category=Category.ENGLISH, scheduledAt="2026-10-28T15:00", resourceUrl=url, note=note)

    @Test fun primaryAndSecondVideoKeepNamesAndOrderWithoutDuplicatingPrimary() {
        val r=LearningResources.from(course(note="全部资源：\n视频1：https://example.org/one\n视频2：https://example.org/two\n辅助工具：https://chatgpt.com/"))
        assertEquals(listOf("https://example.org/one","https://example.org/two","https://chatgpt.com/"),r.map{it.url})
        assertEquals(listOf("视频1","视频2","辅助工具"),r.map{it.label})
    }
    @Test fun unsafeOrCredentialledLinksAreNotOpenable() {
        val r=LearningResources.from(course("file:///C:/private", "来源：https://name:password@example.org/private\n工具：javascript:alert(1)"))
        assertTrue(r.isEmpty())
    }
    @Test fun selectionsStopBeforeSimplificationAndResources() {
        val note="指定选集：\n第2节：什么是智能体AI？\n第6节：任务分解\n精简内容：只看一节\n全部资源：\n视频：https://example.org"
        assertEquals(listOf("第2节：什么是智能体AI？","第6节：任务分解"),LearningResources.selections(note))
    }
    @Test fun legacySingleResourceAndNoSelectionsRemainSupported() {
        assertEquals(listOf(LearningResource("主资料","https://example.org/one")),LearningResources.from(course()))
        assertTrue(LearningResources.selections("原有备注").isEmpty())
    }
    @Test fun resourcesInNotesCanBeUsedWithoutMainLink() {
        assertEquals("http://192.168.0.23:8081/lesson",LearningResources.from(course("","资料：http://192.168.0.23:8081/lesson")).single().url)
    }
    @Test fun mealReferencesDoNotBecomeLearningResources() {
        assertTrue(LearningResources.from(course().copy(category=Category.MEAL)).isEmpty())
    }
}
