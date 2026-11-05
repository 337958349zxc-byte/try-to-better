# try to better

Android 原生个人计划执行工具：把学习、训练、饮食和生活安排放在同一日程中，记录实际执行，并通过配对电脑进行同步与复盘。

**当前源码基线：0.2.19 / code21。** 这是经过整理的项目讨论快照，适合阅读源码、评估架构和讨论产品方向。个人运行数据库、真实菜单、私人日程、配对信息、签名密钥和本地配置未纳入仓库。

## 从这里开始

- **[项目讨论稿 PROJECT_BRIEF.md](PROJECT_BRIEF.md)**：可直接交给其他聊天阅读，包含项目目标、现有能力、限制与商业讨论问题。
- [架构与源码导航](docs/ARCHITECTURE.md)
- [当前状态与验证范围](docs/STATUS.md)
- [同类项目与商业假设](docs/PRODUCT_DISCUSSION.md)
- [历史界面截图](docs/SCREENSHOTS.md)：0.2.17的隔离测试夹具截图，展示五主页面；不代表0.2.19新增页面或真实用户数据。

## 已实现的能力

| 模块 | 当前实现 |
| --- | --- |
| 日程与执行 | 今天、日历、计划、记录、我的；开始/暂停/继续，完整/部分/跳过，改期与历史订正 |
| 重复安排 | 按天间隔、按周选日及截止日期；最长一年；截止日期内完整生成实例，支持本次/后续范围与冲突提示 |
| 周期复盘 | 7天/14天周期、计划与实际分别统计、成果原因及训练恢复、Markdown导出 |
| 本地保存 | Room保存快照，执行开始快照、离线修改及完成历史保护 |
| 电脑协作 | Python/SQLite局域网伴随服务、设备配对、双向同步、逐项冲突与操作回执 |
| 学习资料 | 多命名资源、视频选集、手机打开和请求配对电脑打开 |
| 训练 | 七动作模板、逐组实际次数、左右侧、RPE/不适/恢复、暂停草稿与关联事项保存 |
| 姿态辅助 | 本机CameraX/MediaPipe；限定椅子动作与墙壁模式的辅助计数，手工核对，动作质量未评分 |
| 数据与更新 | JSON备份/恢复、CSV导出；更新包检查大小、哈希、包名、版本和签名 |

AI/专业内容目前由外部工作流交接，App尚未内置可独立服务新用户的通用AI自动规划引擎。训练计数准确性、手机后台提醒与部分真机流程仍需实际验收。

## 工程结构

```text
android/              Kotlin / Compose / Material 3 / Room 原生App
server/               Python标准库HTTP + SQLite服务及电脑工作台
scripts/              本地发布与专项检查辅助脚本
菜谱网页/dist/        无个人内容的菜谱入口占位页
docs/                 架构、状态、讨论材料、历史截图与快照清单
```

目录及应用ID沿用早期拼写`try-to-batter`和`com.trytobatter.app`，产品显示名为`try to better`。

## 构建Android

需要JDK 17及以上、Android SDK 36和Build Tools 36.1.0。Gradle Wrapper与模型文件已包含。

在`android/local.properties`指定本机SDK，例如：

```properties
sdk.dir=C:/Android/Sdk
```

Windows：

```powershell
.\android\gradlew.bat -p android :app:assembleDebug
```

Linux/macOS：

```bash
./android/gradlew -p android :app:assembleDebug
```

输出为`android/app/build/outputs/apk/debug/app-debug.apk`。本仓库不携带个人预览包或签名密钥；自行构建不代表已通过手机验收。

## 运行电脑服务

需要Python 3.10及以上，无第三方Python依赖：

```bash
python server/server.py --port 8081
```

电脑浏览器打开`http://127.0.0.1:8081`，手机在同一可达局域网内进行配对。服务运行后创建的`server/data/`已被忽略。当前部署目标是局域网，互联网连接与TLS尚未实现。

菜谱入口在本快照显示占位说明；个人菜单和营养资料保留在原使用环境。更新清单`server/release.json`由部署者配置，未携带个人发布通道元数据。

## 检查

服务端检查使用临时数据库和临时HTTP端口：

```bash
cd server
python -m unittest -v test_server
```

相关Android单元检查：

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest --tests com.trytobatter.app.RepeatRulesTest --tests com.trytobatter.app.PeriodReviewTest --tests com.trytobatter.app.TaskRulesTest --tests com.trytobatter.app.UnifiedTrainingTest
```

0.2.19原预览包的记录为30项相关单元与20项服务端检查通过，具体范围见[验证记录](docs/verification/0.2.19-release-checks.json)。这与本仓库后续CI、完整回归及手机验收分别记录。Android仪器测试仅应对隔离设备运行；`PoseModelSmokeTest`依赖单独提供的测试图片，详见[测试图片说明](android/app/src/androidTest/assets/README.md)。

## 第三方资料

依赖和模型说明见[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。本次上传没有为项目整体新增开源许可证，第三方组件保留其各自许可说明。
