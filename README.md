# recording-camera — 相机核心库

从 Alibi（`app.myzel394.alibi`）的相机实现中提取、重构并补强的 **纯核心库**：拍照、录像、变焦、对焦、闪光/手电、扩展（夜拍/HDR）、三种存储目标与崩溃恢复。
**本模块不含任何界面**，所有交互通过 `StateFlow` / `SharedFlow` 与挂起函数暴露，宿主（Activity、Service、Compose 页面）自行决定 UI。

- 模块：`:camera-core`（`com.android.library`，namespace `com.recordingcamera.core`）
- 依赖：CameraX 1.5.3、Kotlin 2.0.21、AGP 8.9.1、minSdk 24、compileSdk 36

## 相对原实现的关键改动

| 原实现（Alibi） | 本库 |
| --- | --- |
| 拍照服务与录像服务各自绑定相机，模式切换整轮重绑 | 单个 `CameraCore` 会话同时绑定 Preview + ImageCapture + VideoCapture，变焦/对焦/手电只有一份所有权 |
| 变焦用 32 ms 时间戳队列，可能出现“上一帧未完成即丢弃下一帧” | `Channel(CONFLATED)` + 单次在途 `setZoomRatio` + 150 ms 看门狗，结构上就是 latest-wins，硬件卡顿不会拖死 ticker |
| 对焦成功/失败靠回调 + 分散的 generation 变量 | 统一 `FocusController`，`StateFlow<FocusState>`，含 AF_STATE 扫描态校验（拒绝陈旧锁焦）与 2.5 s 超时 |
| 状态是若干可变字段 + 全量 `publish()` | 单一不可变 `CameraCoreState`，变焦只做窄更新，避免无谓重组 |
| 错误是字符串消息 | `sealed class CameraError`，12 种可判别的失败原因 |
| 存储散落在 Helper 中 | `PhotoSink` 抽象 + 持久化日志（拍摄前落盘记录），进程被杀后可恢复 |
| 录像状态由服务内散落字段维护 | `VideoRecordingController` 状态机 Idle → Preparing → Recording ⇄ Paused → Finalizing，含时长/文件上限自动停止与 15 s 收尾看门狗 |

## 目录结构

```
camera-core/src/main/java/com/recordingcamera/core/
├── CameraCore.kt            门面：会话、绑定、拍照/录像入口、缩放/对焦/手电
├── CameraCoreState.kt       状态与事件（含 SavedPhoto / SavedVideo）
├── CameraCapabilities.kt    设备能力快照（变焦范围、ZSL、OIS、CAF、硬件 JPEG 上限…）
├── CameraError.kt           类型化错误
├── Config.kt                全部配置数据类与枚举
├── internal/                绑定与用例构建（降级链、扩展解析、能力读取、拍摄参数策略）
├── zoom/                    SmoothZoom（帧率无关缓动）+ ZoomDispatcher + ZoomController
├── focus/                   FocusController + FocusAfTracker（AF 状态机）
├── photo/                   拍摄控制器、JPEG 策略、目标分辨率、在途配额
├── video/                   录像控制器、防抖策略、输出分配（私有目录/相册/SAF）
├── storage/                 PhotoSink 三件套 + 待处理照片日志
└── util/                    Future 适配、命名、生命周期宿主、屏幕旋转来源
```

## 快速开始

```kotlin
val core = CameraCore(context, CameraCoreConfig(
    lens = CameraLens.BACK,
    extension = ExtensionPreference.AUTO,          // 夜拍 > HDR，不支持则静默回退
    photo = PhotoConfig(destination = MediaDestination.Gallery("Pictures/RecordingCamera")),
    video = VideoConfig(destination = MediaDestination.Gallery("Movies/RecordingCamera")),
))

// 状态与事件
lifecycleScope.launch {
    core.state.collect { state -> render(state) }        // 单一状态源
}
lifecycleScope.launch {
    core.events.collect { event -> when (event) {
        is CameraCoreEvent.PhotoSaved     -> toast(event.photo.displayName)
        is CameraCoreEvent.VideoSaved     -> toast(event.video.displayName)
        is CameraCoreEvent.VideoAutoStopped -> toast("已按上限停止：${event.reason}")
        is CameraCoreEvent.PhotoSaveFailed  -> toast("保存失败：${event.error.message}")
        else -> Unit
    } }
}

// 打开会话（服务里请自己驱动 SimpleLifecycleOwner，见下文）
lifecycleScope.launch {
    core.open().onFailure { /* 已经归一化成 CameraError */ }
}

core.setPreviewSurfaceProvider(previewView.surfaceProvider)
core.setMeteringPointFactory(previewView.meteringPointFactory)

core.capture()                                  // 拍照
core.startRecording()                           // 开始录像
core.stopRecording()                            // 停止并等待 VideoSaved

core.setZoomRatio(2.0f)                         // 绝对倍率：立即生效
core.zoomBy(1.1f)                               // 双指相对：16 ms ticker 缓动
core.focusAt(x, y)                              // 挂起，返回 FocusState
core.setFlashMode(FlashMode.AUTO)               // 不重绑，直接生效
core.setTorch(true)
```

在**前台服务**中使用（没有 Activity 生命周期）：

```kotlin
class CameraService : Service() {
    private lateinit var owner: SimpleLifecycleOwner
    private lateinit var core: CameraCore

    override fun onCreate() {
        owner = SimpleLifecycleOwner().also { it.resume() }
        core = CameraCore(this)
    }

    override fun onDestroy() { core.close(); owner.destroy() }
}
```

> `CameraCore` 内部已自带 `SimpleLifecycleOwner`；上例演示的是“宿主自己控制生命周期”的方式，两种都可用，`open()` 时会自动 `resume()` 内部实例。

## 主要 API

| 分类 | 成员 |
| --- | --- |
| 会话 | `open(config)`、`switchLens(lens)`、`updateConfig(config)`、`close()`、`updateRotation()`、`config` |
| 预览 | `setPreviewSurfaceProvider(SurfaceProvider?)` |
| 拍照 | `capture()`、`pendingPhotos`、`retryFailedPhotoSaves()`、`setFlashMode()` |
| 录像 | `startRecording()`、`stopRecording()`、`pauseRecording()`、`resumeRecording()` |
| 变焦 | `setZoomRatio()`、`setZoomFraction(0..1)`、`zoomBy()`、`stepZoom()`、`cancelZoomAnimation()` |
| 对焦 | `setMeteringPointFactory()`、`suspend focusAt(x, y, size)`、`cancelFocus()` |
| 手电 | `setTorch(enabled): Result<Unit>`、`torchAvailable` |
| 观测 | `state: StateFlow<CameraCoreState>`、`events: SharedFlow<CameraCoreEvent>`；派生 `state.linearZoom`、`state.canCapturePhoto`、`state.isFinalizing` |
| 静态助手 | `CameraCore.requiredPermissions()`、`missingPermissions(context)`、`isSupported(context, lens)` |

`updateConfig(config)` 会先比对重绑需求（`ConfigDiff`）：只改目的地、闪光、码率、音量、时长上限时就地生效、预览不中断；改镜头、扩展、画质档、宽高比、防抖、帧率、在途配额则走一次 `open()` 重绑，录像进行中调用会返回 `InvalidState` 而不是把录像打断。

### Java 互操作

`PhotoConfig` / `VideoConfig` / `CameraCoreConfig` 带 `@JvmOverloads`，Java 可只传关心的字段；返回 `Result<Unit>` 的 6 个入口带显式 `@JvmName`（`open`/`switchLens`/`updateConfig`/`capture`/`startRecording`/`setTorch`），JVM 侧名字不含 Kotlin 的值类后缀，Java 与反射都能稳定按名调用。公开签名由 `CameraCoreApiContractTest` 锁定，删改成员会先在这里失败。

### 存储目标

```kotlin
MediaDestination.AppPrivate                              // filesDir/…，配合 FileProvider
MediaDestination.Gallery("Pictures/RecordingCamera")     // MediaStore（<Q 回退到 DCIM + 扫描）
MediaDestination.DocumentTree(treeUri, "MyCam")          // SAF 目录，任意 DocumentProvider
```

### 错误

`CameraError` 子类：`NotReady`、`PermissionMissing`、`InUse`、`Disconnected`、`CameraUnavailable`、`BindFailed`、`SessionClosed`、`CaptureFailed`、`QueueSaturated`、`StorageFailed`、`StorageInsufficient`、`RecordingFailed`、`Timeout`、`Unsupported`、`InvalidState`。全部可 `when` 分支，不做字符串匹配；`error.isRecoverable` 直接回答“重试有没有意义”（`PermissionMissing`、`SessionClosed`、`CameraUnavailable`、`InvalidState` 为 `false`，其余为 `true`）。`setTorch` 现在返回 `Result<Unit>`：`NotReady`（无会话）与 `Unsupported`（无闪光灯）在这里当场返回，而不是静默忽略。

## 内建稳健性

- **绑定降级链**：`Preview+Capture+Video+Analysis` → 去 Analysis → 去 Video → 默认预览分辨率，四步全失败才报 `BindFailed`。
- **拍摄配额**：默认 8 张 / 128 MiB 在途，超限立即拒绝并报 `QueueSaturated`（快门不会被无声丢弃）。
- **崩溃恢复**：拍照前把待处理记录写入 `filesDir/camera-core/pending_photos.tsv`，下次 `open()` 自动重投递并发布 `RecoveredPhoto`；索引损坏时自动丢弃而不是卡死。
- **保存串行化**：单 worker 消费保存队列，MediaStore 慢提交不会打乱顺序；`close()` 只停止接单，已入队照片仍会写完。
- **录像收尾**：`Finalize` 事件 15 s 未到达则强制回到 `Idle` 并报 `Timeout`，避免服务卡在 `Finalizing`。
- **变焦幂等**：绝对输入立即 `snapTo`，相对输入累加后缓动；镜头切换前记录倍率，重绑后自动恢复并夹到新范围。
- **对焦防陈旧**：只有出现过扫描态（PASSIVE/ACTIVE_SCAN）或状态发生变化后的 `FOCUSED_LOCKED` 才算成功，恒定焦点算法不会误报。

## 构建与验证

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot"
./gradlew :camera-core:assembleDebug :camera-core:testDebugUnitTest :camera-core:lintDebug
```

当前状态：**BUILD SUCCESSFUL**，92 个 JVM 单测全绿，lint 0 error。
纯 Kotlin 逻辑（缩放、配额、JPEG 策略、目标尺寸、防抖策略、命名、日志、配置差量、变焦映射）不依赖 Android，全部可单测；公开签名由反射契约测试锁定。

## 已知限制

- 录像 during 旋转不会改变视频方向（CameraX 把 rotation 烘进已绑定的用例），需要 `open()` 重绑；拍照可通过 `updateRotation()` 即时纠正。
- SAF 录像输出走 `FileDescriptorOutputOptions`，需要 API 26+；更低版本请用相册或私有目录。
- 扩展（夜拍/HDR）由厂商实现决定，`ExtensionPreference.AUTO` 在不支持的机型上静默回退到标准管线。
- `targetSdk 34`：为与 Alibi 保持一致；库本身不声明 targetSdk 行为，最终以宿主为准。
