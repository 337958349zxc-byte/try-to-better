# Android 桌面大肥鱼 · 系统悬浮窗 V1

版本 **0.2.21 / code23**。由“我的 → 桌面大肥鱼”开启后，角色直接显示在手机桌面和普通应用上方。使用Android原生 `WindowManager` / `TYPE_APPLICATION_OVERLAY`，不依赖预览页持续打开。

## 手机使用

1. 安装0.2.21，在“我的”打开“桌面大肥鱼”。
2. 点击“开启桌宠”，在系统页面允许“显示在其他应用上层”，再返回App。Android13及以上如提示通知权限，可允许，以便在通知栏关闭桌宠。
3. 点击“回到桌面看看”，或按手机Home手势。角色仍显示在真实桌面上。
4. 拖动角色，松手自动贴近左右边缘；点击角色打开轻气泡。气泡可打开离线演示聊天、桌宠设置或直接关闭桌宠。点击气泡外侧会收起，12秒后也会自动收起。
5. 在设置中调整大小、停靠侧、角色动画、安静陪伴。设置与位置独立保存，下次开启复用。
6. 可从气泡、设置页或常驻通知关闭。锁屏时角色隐藏并停止动画，解锁后恢复。设备重启、强制停止或系统结束进程后不自动重启，需要再次开启。

聊天目前仍为本地模拟回复，不接入DeepSeek API；没有修改或读取真实日程、训练及执行记录。原有页面内预览也保留为独立的动作展示入口。

## 实现与边界

- `feature/petoverlay/PetOverlayActivity.kt`：独立授权、开启/关闭及设置页面。实际复查 `Settings.canDrawOverlays`，拒绝授权时不会启动。通知权限结果与悬浮窗授权分别处理；不把通知授权当成悬浮窗授权。
- `PetOverlayService.kt`：用户在前台明确开启后启动前台服务，添加小型透明悬浮窗口；拖动、靠边、气泡、通知关闭、权限检查、锁屏隐藏及销毁清理均由此服务管理。窗口之外的触摸交给下面的应用，不建立全屏遮罩。角色画布内透明留白仍属于小窗口的触摸范围，不能理解为所有透明像素自动穿透。
- `PetOverlayView.kt`：原生透明Canvas，直接绘制完整RGBA图集帧。待机眨眼、互动挥手、拖动姿态及安静睡眠均沿用原动作；没有重绘、变形或白色图片背景。屏幕熄灭时停止播放。
- `PetOverlayConfig.kt`：独立SharedPreferences，仅保存大小、动画、安静状态、靠边及纵向比例。运行状态来自实际服务生命周期，不持久保存一个虚假的“已开启”。
- 计算状态栏、导航栏、刘海和可得输入法Insets的安全区域；预留12dp边缘距离，保持完整角色画布。屏幕方向或可用区域变化后重新限制位置。复杂折叠、分屏、各厂商键盘与省电策略仍需真机验收。
- 新增权限只有 `SYSTEM_ALERT_WINDOW` 和 `FOREGROUND_SERVICE_SPECIAL_USE`。沿用原有的前台服务/通知基础权限，但不复用闹钟服务、闹钟通知或系统豁免类型。
- Android14起采用 `specialUse` 服务类型，并声明用途说明，参见[Android官方前台服务类型要求](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)。本地实现与构建不等于通过应用商店的服务用途审核；若上架需另行申报与评估。
- 启动来自用户在前台的明确操作，不依赖后台启动豁免、不申请开机自启或电池优化豁免。Android15的后台启动限制见[官方说明](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-overlay)。

## 素材与授权

作者：**YunYueSama**，来源：[codex-deepseek-pet](https://github.com/YunYueSama/codex-deepseek-pet)，固定版本 `7661c8b304c5400701f91da01b1a643a207331de`。复用0.2.20中已验证的透明完整帧及头像，未新增或重绘角色图片。

完整原许可、作者与仓库、逐文件原始/输出哈希、处理说明仍保留在 `android/app/src/main/assets/petpreview/`，可从桌宠设置中查看。原始素材仅进行了完整帧选取、等比缩小、图集排列和无损编码，头像取原立绘头肩。完整授权清单及第三方角色/社区参考权利边界见[视觉设计V1](ANDROID_PET_DESIGN_V1.md#素材与授权)。本轮不会扩大上游授权范围。

## 验证和截图

实际运行结果与精确安装包哈希记录在 `verification/overlay-build-and-ui.json`；截图位于 `screenshots/overlay/`。使用专用模拟器，独立启动桌宠设置Activity，不启动业务MainActivity或重置数据。桌面截图必须同时验证底层焦点属于Launcher、存在系统悬浮窗口，不能用应用内页面代替。

[10张实际截图与口径](OVERLAY_SCREENSHOTS.md)。两项Android交互检查覆盖授权拒绝和允许、退出到Launcher、点击/拖动/贴边、聊天与回复、大小及安静姿态、关闭及通知移除、可用显示区域变化，以及通知关闭动作。区域变化通过专用模拟器从1080×2400切换至1080×2160验证，不代表实体设备传感器旋转或横屏验收。另有34项相关单元检查；精确构建、Lint及签名结果以验证记录为准。

本轮修改文件：新增 `feature/petoverlay/` 下的Config、View、Service、Activity四个文件，新增PetOverlayRulesTest与PetOverlayUiTest；Manifest新增上述两权限及两个不导出的组件；BatterApp/ProfileScreen仅新增入口；PetArtwork仅将已有图集缓存开放为模块内部共享；build.gradle.kts仅升版本。文档与实际截图同步更新。没有新增依赖、改写业务模型/Room/同步/训练或重新处理角色素材。

实体手机的系统授权、后台留存、滑动手势、耗电与现代Android版本表现由手机验收确认；构建与模拟器结果不能替代这些结论。实际桌宠保持静音，安静陪伴开关用于睡眠姿态。
