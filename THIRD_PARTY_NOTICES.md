# 第三方组件与模型

| 组件 | 用途 | 来源/说明 |
| --- | --- | --- |
| AndroidX Compose / Material 3 / Navigation / Lifecycle / Activity / Core | 原生界面、导航和生命周期 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，Apache-2.0 |
| Room / WorkManager | 本地持久化及后台工作 | [AndroidX](https://developer.android.com/jetpack/androidx)，Apache-2.0 |
| CameraX | 相机预览和帧分析 | [CameraX](https://developer.android.com/media/camera/camerax)，Apache-2.0 |
| MediaPipe Tasks Vision | 本机姿态模型推理 | [MediaPipe](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)，现有源码使用1.1.1 |
| Kotlin / kotlinx.coroutines | 语言与异步操作 | [Kotlin](https://github.com/JetBrains/kotlin)、[Coroutines](https://github.com/Kotlin/kotlinx.coroutines)，Apache-2.0 |
| Material Icons | 界面图标 | [Material Icons](https://github.com/google/material-design-icons)，Apache-2.0 |
| Gradle Wrapper | 构建启动器 | [Gradle](https://github.com/gradle/gradle)，Apache-2.0 |

本快照实际包含`android/app/src/main/assets/pose_landmarker_lite.task`。其随原工程保存的[模型来源和哈希说明](android/app/src/main/assets/pose-model-NOTICE.txt)及[许可文件](android/app/src/main/assets/mediapipe-LICENSE.txt)一并保留。模型来源记录指向Google/MediaPipe发布的Pose Landmarker Lite float16 revision 1。

示例任务中的外部课程资源是浏览器链接，未复制对应教学视频或网站正文。对比文档引用的wger、Mealie、Anki等项目用于产品研究，其程序没有被打包进本App。

本次整理没有为项目整体新增开源许可证；第三方组件与模型继续适用其各自的许可和说明。
