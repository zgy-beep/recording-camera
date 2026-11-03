# camera-core 核心升级路线图（稳定 / 性能 / 速度）

> 范围：`camera-core` 库内核。**不含任何 UI 设计**（`PreviewView` 由宿主提供，本库只提供能力）。
> 依据：源码逐行审计（CameraX 1.5.3 实际字节码核对）+ 官方 1.6/1.7 release notes + 同类优秀项目实现。
> 所有结论都带 `文件:行号`，未标注"真机待测"的均为代码事实。

---

## 0. 一句话结论

内核的**架构选择是对的**（单会话多 use case、串行保存队列、先记账再拍、防抖包络），比 Alibi 上游"每切模式整轮重绑"领先一个档次；
真正的欠账集中在五处：**① 相机生命周期完全没观测、② 若干失败路径没有清场（会越用越坏）、③ 录像的"失败语义"整体是反的——`hasError()` 即删文件、`Finalize` 不是唯一出口、`close()` 直接丢 output、没有 `moov`/分段兜底、④ 打开/快门关键路径上有 3 段可白拿的延迟、⑤ 没有用上 CameraX 1.6 起才有的兜底能力（Media3 muxer / Feature Group / CameraPipe）**。

按本文 M0→M4 顺序推进，预期可以拿到：

| 指标 | 现状（估算） | 目标 | 主要来源 |
|---|---|---|---|
| `open()` → `READY` p50（冷启） | 575–1770 ms | 100–300 ms | 预热 + 扩展惰性化 + 能力缓存（B1/B2/B4） |
| `open()` → `READY` p50（切镜头/重绑） | 375–1330 ms | 50–200 ms | 去掉 `unbindAll`（B3） |
| 快门 → 有视觉反馈 p95 | 500–2000 ms | ≤150 ms | postview + MediaStore 直写 + journal 移出主线程（B7/B8/B9） |
| 连续录像 2 h | 进程被杀/断电后 MP4 可能不可播 | 可播（或可恢复） | CameraX 1.6 Media3 muxer（C1） |
| 被其他 App 抢占相机 | 永久卡在 `READY` | 自动恢复或明确 `INTERRUPTED` | `CameraState` 观测（A1） |
| 连拍 100 张 + 中途 rebind | 可能出现重复照片 / 孤儿 `.part` | 0 重复、0 孤儿 | journal 所有权 + part GC（A3/A4） |
| 开了防抖的中低端机 | 「预览正常，但 `startRecording` 永远 `NotReady`」（能力"或"合并导致 bind 抛错后被降级链吃掉） | 一次 bind 成功或明确报 `StabilizationDegraded` | S13/S16/S17 + C2 |
| 磁盘写满 | 删除唯一有效素材（`ERROR_INSUFFICIENT_STORAGE` 被 `hasError()` 判为失败） | 保留文件 + `VideoPartialSaved(StorageFull)` | A4 ④ |

---

## 1. 对标：从这些项目直接抄什么

| 项目 | 抄什么 | 对应条目 |
|---|---|---|
| `androidx` **camera-samples**（CameraXBasic / CameraXAdvanced / CameraXVideo） | `cameraInfo.cameraState.observe` 驱动状态机；`startFocusAndMetering` future 为唯一对焦结论（不自设更短超时）；不对 `ImageCapture` 钉 `CONTROL_AF_MODE`；`setLinearZoom`；`UseCaseGroup` + `ViewPort`；切换画质用 rebind 而非 `unbindAll` | A1、A2、B3、B10、C5 |
| **GrapheneOS Camera** | `ImageAnalysis` 用独立 `HandlerThread`；`DisplayListener` 驱动旋转；app 级 saver 单例 + 持久化队列表（保存不属于 use case 生命周期）；`cameraState` 的 `CLOSED/ERROR` 重建会话 | A3、B5、B6、B10 |
| **AOSP Camera2Video / MediaProvider** | 写失败即删除已插入的 pending 行；`_display_name` 用 UTC 时间戳；`MediaScannerConnection` 删除后必须重扫 | A5、B9、C3 |
| **Alibi**（本库上游） | 扩展 OFF 先短路再取 manager；`withContext(Dispatchers.IO)` 包住 provider/manager；`ZoomRequestQueue` 的"单飞 + 最新值覆盖 + 不把延迟当失败" | B1、B4、B10 |
| **AndroidX Media3** | 1.6 起 `VideoCapture` 默认 Media3 muxer，进程被杀也不产生不可播 MP4；`Recorder` 音频参数（1.7） | C1、C4 |
| **OpenCamera** | 机型 quirk 表（按 `Build.MODEL` / `INFO_SUPPORTED_HARDWARE_LEVEL` 关掉不可用组合），用于替代"4 步试错" | B6 |
| **CameraX 1.6 `CameraXConfig`** | `setAvailableCamerasLimiter` + 预热，砍掉首帧前 300–700 ms 的 provider 初始化 | B2 |

---

## M0 立即可做（低风险，1–2 天，先止血）

**这 6 组改动大多在 10 行以内（A4 会牵出录像收尾的统一改造），但分别对应"进程崩溃""永久卡死""越用越坏""满盘删素材"四类事故，应当先合并。**

### A1 订阅 `CameraState`，`READY` 只在 `OPEN` 后置位

- **证据**：全库 `grep cameraState` 0 命中；`CameraCore.kt:284-295` 在 `bindToLifecycle` 返回后直接置 `READY`，而 CameraX 此时通常还是 `PENDING_OPEN/OPENING`；`CameraCore.kt:348` 只看 `status == READY` 就放行拍照。
- **机制**：被其他 App 抢占、系统回收、`ERROR_CAMERA_IN_USE` 时设备已 `CLOSED`，但库内状态永久 `READY`：`capture()` 继续走死管线，宿主只看到"拍照失败"，画面冻结却显示正常。
- **改法**：

```kotlin
// bindSession 末尾
bound.camera.cameraInfo.cameraState.observe(mainExecutor) { s ->
    when (s.type) {
        CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING ->
            _state.update { it.copy(status = CameraStatus.OPENING) }
        CameraState.Type.OPEN ->
            _state.update { it.copy(status = CameraStatus.READY, error = null) }
        CameraState.Type.CLOSED ->
            fail(CameraError.SessionLost())            // 新增错误类型
        CameraState.Type.ERROR ->
            fail(CameraError.CameraStateError(s.error?.code, s.error?.cause))
    }
}
```

`CameraStatus` 增 `INTERRUPTED`；`close()` / 重绑前 `removeObservers`。
`READY` 后另发一次 `CameraCoreEvent.FirstFrame`（`PreviewView` 首帧回调由宿主触发），让宿主把"打开耗时"打在真实首帧上。

- **验收**：Monkey/抢占测试下 `status` 能在 5 s 内从 `READY` 回到 `INTERRUPTED`；`capture()` 在 `INTERRUPTED` 下返回可识别错误而不是 `CaptureFailed`。

### A2 对焦：超时预算重排 + 跨线程状态机单线程化

- **证据**：`FocusController.kt:174` `DEFAULT_TIMEOUT_MILLIS = 2_500`；`FocusController.kt:171` `FOCUS_AUTO_CANCEL_SECONDS = 3`；CameraX 1.5.3 内部 `AUTO_FOCUS_TIMEOUT_DURATION = 5000 ms`（aar 实测）。三者倒挂：**2.5 s < 3 s < 5 s**，低光/微距场景一定先报 `TIMEOUT`，随后设备真实上报的 `FOCUSED_LOCKED` 因 `deferred.isCompleted` 被丢弃（`FocusController.kt:162`），状态永不修正。
  同时 `generation`/`pending`/`tracker` 全是普通字段（`FocusController.kt:38-42`），由主线程写、相机线程读（`:52-57`），无 volatile/锁 → 可能用**上一次点击**的 LOCKED 完成新请求（假对焦）。
- **改法**：① 预算改 `setAutoCancelDuration(5, SECONDS)` + 我方 `timeoutMillis = 6_000`（只作兜底）；② `onCaptureCompleted` 只做 `afStates.trySend(afState)`（`Channel(CONFLATED)`），`tracker`/`settle` 全部收敛到 `Dispatchers.Main.immediate` 消费；③ `settle` 用 `if (!deferred.complete(state)) return`。
- **验收**：新增单测"applyZoom/focus 在异常下不逃逸"；低光真机上 `FocusState` 在 ≤6 s 内必收敛，且不会出现 `Focused` 之后又 `Failed`。

### A3 变焦管线封住异常逃逸

- **证据**：`ZoomDispatcher.kt:52-62` `scope.launch { drain() }` 内无 `try/catch`；`ZoomController.kt:148-151` `awaitValue` 把 `future.get()` 的 `ExecutionException` 原样抛出（`Futures.kt:33`）；`CameraCore.kt:64` 只有 `SupervisorJob`，**无 `CoroutineExceptionHandler`** → 一次相机抖动 = 主线程未捕获异常 = 进程死。附：`drain` 死后 channel 只剩最新值，变焦静默失效。
- **改法**：

```kotlin
// ZoomDispatcher.drain()
val completed = try {
    withTimeoutOrNull(watchdogMillis) { applyZoom(ratio); true } ?: false
} catch (c: CancellationException) { throw c }
catch (t: Throwable) { failedCount++; false }

// CameraCore
private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate +
    CoroutineExceptionHandler { _, e -> _state.update { it.copy(error = CameraError.Unexpected(e)) } },
)
```

同时 `submittedRatio` 只在**成功回调**里推进，失败置 `NaN`（现在在提交时就推进，`ZoomController.kt:144`，一旦超时该比例永久不再下发）。
- **验收**：单测"applyZoom 抛异常 → 后续请求仍被处理、failedCount 递增"；真机拔插/抢占后进程不崩。

### A4 录像 `Finalize` 必须是唯一收尾出口（看门狗 / `close()` / 错误码）

- **证据 1（看门狗不清场）**：`VideoRecordingController.kt:272-282` 超时只 `state.video = Idle` + 发 `VideoFailed(Timeout)`，**不清理 `recording`**；`start()` 在 `recording != null` 时直接拒绝（`:60-63`，`isActive` = `synchronized(lock) { recording != null }`，`:56`）；`CameraCore.kt:122-126` 又用 `video?.isActive` 拒绝 `open()` → 一次挂死 = 该进程**永久不能录像、不能重绑**。更糟：看门狗挂在 `scope` 上，`CameraCore.close()` 的 `scope.cancel()`（`CameraCore.kt:179`）会直接把它取消 → 连兜底都没了。`stop()` 又无条件重挂（`:142`），而 `onStatus` 每秒调 `stop()`（`:201`）→ 15 s 窗口被无限续期。
- **证据 2（`close()` 丢弃 output）**：`VideoRecordingController.kt:156-164` `runCatching { active?.close() }; synchronized(lock) { recording = null; output = null }`，**从不调用 `output.finalize`**；`detachControllers`（`CameraCore.kt:302-313`）同。后果：`onFinalize`（`:222-232`）里 `allocated?.finalize(...)` 因 `output == null` 整句跳过 → AppPrivate 留半成品 MP4、MediaStore 行永远 `IS_PENDING=1`、SAF 的 `ParcelFileDescriptor`（`VideoOutputFactory.kt:214`）不关闭 → fd 泄漏到后续 `openFileDescriptor` 抛 `FileNotFoundException`。Alibi 的做法是先 `stopActiveRecording()` 再 `withTimeoutOrNull { finalizer.await() }` 最后才 `closeCamera()`（其注释：Camera can only be closed after the recording has been finalized）。
- **证据 3（错误码一律删除）**：`:228-232` `val failed = event.hasError(); allocated?.finalize(!failed, resolvedUri)`；CameraX 1.5.3 的 `hasError()` 就是 `mError != 0`，只有 `ERROR_NONE` 例外 → `ERROR_INSUFFICIENT_STORAGE` 时文件里已有有效 `mdat`（缺 `moov` 可修）却被 `VideoOutputFactory.kt:128-130/142-146/180-186` 三处 `delete` 删掉；未来启用 `setDurationLimit`/`setFileSizeLimit` 后 `ERROR_DURATION_LIMIT_REACHED`/`ERROR_FILE_SIZE_LIMIT_REACHED` 也会被当成失败删除。
- **改法**：① `stop()` 返回 `CompletableDeferred<Unit>`，`close()`/`detachControllers` 里 `withTimeoutOrNull(3_000) { finalizer.await() }` 后再置空；超时则 `synchronized(lock) { output?.also { output = null }; recording.also { recording = null } }?.finalize(false, null)`。② 看门狗移到独立 `SupervisorJob`（或 `Handler.postDelayed`），`armFinalizerWatchdog` 幂等（`isActive` 则 return）。③ 超时分支必须清对象图（`recording?.close()` + `finalize(false, null)`）。④ 错误码白名单：`ok = error == ERROR_NONE || ERROR_DURATION_LIMIT_REACHED || ERROR_FILE_SIZE_LIMIT_REACHED`；`INSUFFICIENT_STORAGE` → 保留文件 + `VideoPartialSaved(reason = StorageFull)`。⑤ 新增 `VideoState.Error(reason)`，不要用 `Idle` 掩盖"可能不完整"。
- **验收**：注入"Finalize 永不返回"的 fake，二次 `start()` 能成功；`close()` 后 SAF fd 计数不增长；满盘模拟下文件被保留且事件为 `PartialSaved`；MediaStore 无 pending 残留。

### A5 照片 `.part` 生命周期闭环（含孤儿 GC）

- **证据**：`PhotoCaptureController.kt:146-151`（同步抛异常）、`:211-221`（`onCaptureFailed`）、`:166-170`（零字节）三条失败路径**都不删** temp；`.part` 全仓只在 `:98` 创建，无 sweep、无 `deleteOnExit`。`filesDir` 写满后 `takePicture` 直接 `ERROR_FILE_IO`，journal 的 `writeText` 会连带删表（`PendingPhotoJournal.kt:71`）→ "失败 → 更多孤儿 → 更满"自我放大。
- **改法**：统一 `fun discardPending(task) { runCatching { task.temporaryFile.delete() }; journal.remove(task.id); budget.release(task.reservedBytes) }` 接入三处；Controller 构造时（`Dispatchers.IO`）做一次孤儿 GC：`pendingCaptureDirectory(ctx).listFiles { it.name.endsWith(".part") }` 与 `journal.load()` 的 path 集合做差集删除；目录与 journal 一并搬到 `context.noBackupFilesDir`（见 D5）。
- **验收**：人为让磁盘满后恢复，目录内 `.part` 数回到 0；Auto Backup 恢复后不会在新设备"凭空出现旧照片"。

### A6 让 journal 具备所有权，消灭 rebind 双写

- **证据**：`CameraCore.kt:265-271` 每次 `bindSession` 新建 journal + controller 并立刻 `recoverPending()`；`PhotoCaptureController.kt:158-189` 不看 `entry.stage`；`PendingPhotoJournal.kt:133-134` 所有实例同一路径，而锁是实例级 `private val lock = Any()`（`:30`）。rebind 时旧 worker 还在写同一个 `.part` → 相册出现两张一样的照片，或 `GalleryPhotoSink.kt:195` 抛 `IOException("pending capture ... disappeared")`。
- **改法**：journal 改进程级单例（`@Volatile` + `synchronized` 懒加载）；`Entry` 增 `ownerToken`（`pid-bootId-uuid`）+ `heartbeatAtMillis`，`recoverPending` 跳过"他人持有且心跳 < 30 s"的 `SAVING` 行；更彻底的做法是把保存队列提到 `CameraCore` 之外的 `@Singleton PhotoSaveQueue`，rebind 不再触碰 journal（CameraX 的 `ImageSaver` 就是 per-request 对象，保存与 use case 生命周期解耦）。
- **验收**：rebind 压测（拍照中途切前后摄 ×50）→ `SavedPhoto` 数 == 快门数，无重复、无失败事件。

---

## M0.5 画质 / 运动模糊 / 快门效率（本批已落地）

用户反馈原话："画质我目前不太满意，还有拍照效率，反应灵敏度，性能，移动会模糊等等问题"。
这一批不改 API 形状（`PhotoConfig`/`VideoConfig` 字段未变），只改"同样的配置怎么落到相机请求上"。

### Q1 静态照片尺寸从"越大越好"改成"binning 上限"（已改）

- **证据**：原 `UseCaseFactory.kt` 用 `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` + 固定 4032×3024 目标。48/64/108 MP quad-bayer 传感器上报的"更大档"是**非 binning 读出**：单像素信噪比更差、ISP 降噪被绕过、编码耗时数倍，观感反而更软。
- **改法**：新增 `PhotoTargetSize.captureTarget(aspect, quality)`（`photo/PhotoTargetSize.kt:43-48`）：4:3 → 4032×3024，16:9 → 3840×2160，SPEED → 3264×2448 / 3264×1836；`imageCapture()` 的 fallback 改为 `FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER`（`internal/UseCaseFactory.kt:77-85`），永远不再向上摸到满分辨率档。
- **验收**：108 MP 机型出图长边 == 4032（或 3840），快门→回调耗时下降；`PhotoTargetSizeTest` 覆盖三种 quality × 两种比例。

### Q2 不再在静态请求上钉 `CONTROL_AF_MODE`（已改，画质收益最大的一条）

- **证据**：原 `UseCaseFactory.kt` 对 `ImageCapture` 设 `CONTROL_AF_MODE_CONTINUOUS_PICTURE`。拍摄请求带着这个模式，会在锁焦之后**重新进入连续扫描**；帧在中途采样 → 画面发虚，尤其室内/近摄。
- **改法**：整段删除，并把原因写进注释（`internal/UseCaseFactory.kt:102-106`）。3A 交回 CameraX 的 precapture 序列（它会做 AF/AE 预捕获 + 锁定）。
- **注意**：`CameraCapabilities.continuousAutoFocusSupported` 保留（宿主可读），`CaptureProfile.continuousAutoFocus` 字段已删。

### Q3 OIS 从"仅 SPEED"改为"所有静态模式"（已改）

- **证据**：原 `CaptureProfileFactory` 只在 `PhotoQuality.SPEED` 开 `LENS_OPTICAL_STABILIZATION_MODE_ON`。而 1/30 s 手持抖动就是 12 MP 帧糊掉的主因，BALANCED/QUALITY 反而最容易糊。
- **改法**：`CaptureProfile.opticalStabilization = !extensionActive && opticalStabilizationSupported`（`internal/CaptureProfile.kt`）。扩展（Night/HDR）管线自己管 3A，保持关闭。

### Q4 视频"移动就糊"：OIS + 曝光下限 + 码率（已改）

- **证据**：`VideoOutputFactory.createVideoCapture` 原来只设 `setVideoStabilizationEnabled`（EIS 只是裁剪 + 平移补偿，不解决运动模糊）；不设 `CONTROL_AE_TARGET_FPS_RANGE` 时 AE 可在 15..30 档里把曝光拉到 1/15 s；`createRecorder` 不设码率 → 用 CameraX 面向"发消息"的低码率。
- **改法**：
  - `createVideoCapture(...)` 新增 `opticalStabilizationSupported` / `frameRateRanges` 两个参数，用 `Camera2Interop` 设 `LENS_OPTICAL_STABILIZATION_MODE_ON`；`decision.frameRate == null` 时自动挑 `motionFrameRateRange(ranges)` 并设 `CONTROL_AE_TARGET_FPS_RANGE`（`video/VideoOutputFactory.kt:89-95`）。
  - `motionFrameRateRange`：只保留 `first >= MOTION_FPS_FLOOR(30)` 的档，取**最高上限**（上限越高，传感器可选的最长曝光越短），同上限取最窄区间。
  - `defaultBitRate(quality)`：LOWEST 4 / SD 6 / HD 12 / FHD 20 / UHD 45 Mbps（`video/VideoOutputFactory.kt:72-79`），`config.bitRate` 显式设置时优先。
- **验收**：`VideoCaptureQualityPolicyTest`；真机看 1080p 走动画面纹理（树叶、地砖）不再糊成一片，UHD 码率 ≈45 Mbps。

### Q5 journal 改 append-only（快门路径少一次全表重写）

- **证据**：`PendingPhotoJournal.record()` 原来每次 `writeText(全表)`，且在 `takePicture` **之前**于主线程执行（`PhotoCaptureController.kt:104-113`）→ 连拍 O(n²) 磁盘 IO，直接吃快门响应。
- **改法**：整文件重写为追加日志：`record`/`markSaving` 各追加一行，删除写 tombstone 行（`"-\t<id>"`），死行超过 `entries.size + 8` 时 compact（tmp + rename）；坏行跳过而不是删全表（`storage/PendingPhotoJournal.kt`）。
- **验收**：`PendingPhotoJournalTest` 全绿；连拍 20 张时主线程单次 `record` 为 O(1)。

> 仍未做（按 M1/M2 顺序）：`ImageCapture` 直写 MediaStore（B8，砍掉 filesDir→相册整文件拷贝）、postview（B9，按快门即出图）、视频 Finalize 单一出口（A4）。

---

## M0.6 API 契约（本批已落地）

目标不是"多几个方法"，而是让宿主能**在不重绑的前提下改配置**、能**用 `when` 处理失败**、能让 **Java 宿主按稳定名字调用**。

### P1 配置热更新：`updateConfig(config)`

- **问题**：此前改闪光/目的地/码率也要 `open()`，等于预览黑一下、录像要人停。
- **改法**：新增 `internal/ConfigDiff.requiresRebind(old, new)`，把配置分成两类——
  - **就地生效**：`photo.destination`、`video.destination`、`photo.flashMode`、`video.bitRate`、`video.audioEnabled`、`video.maxDurationMillis`、`video.maxFileBytes`、`rotationSource`、两个 timeout；
  - **需要重绑**：`lens`、`extension`、`bindVideoUseCase`、`keepAnalysisStreamForAutofocus`、`photo.quality/aspectRatio/preferHardwareJpeg/jpegQualityOverride`、`photo.maxPendingCaptures/maxPendingBytes`、`video.quality/aspectRatio/stabilization/frameRate`（都在 `UseCaseFactory`/`CaptureProfile` 构建期解析，或由控制器持有单一实例）。
- **语义**：需要重绑且**正在录像**时返回 `InvalidState`，不打断录像；否则走一次 `open(config)`；无需重绑时就地改 `config`、`session.imageCapture.flashMode`（扩展活跃时强制 OFF）与 `_state` 的镜头/闪光。
- **验收**：`ConfigDiffTest` 9 例覆盖两类划分；就地路径不经过 `open()`（复用同一 `session`），闪光变更可立刻从 `state.flashMode` 读到。

### P2 权限与可用性前置判断

- `CameraCore.requiredPermissions(audioEnabled)` / `missingPermissions(context, audioEnabled)` / `isSupported(context, lens)`，均 `@JvmStatic @JvmOverloads`：宿主可在渲染 UI 前就知道该不该申请权限、这台机器有没有前摄，而不是等 `BindFailed`。
- 内部也接上了：`open()` 先查 CAMERA（缺失直接 `PermissionMissing`，不再白等 provider 5 s），`startRecording()` 先查 RECORD_AUDIO（`audioEnabled=true` 时缺失直接失败，不再靠 `CameraX` 抛 ISE 崩溃）。

### P3 错误模型补齐可恢复性

- 新增 `InUse`（相机被别的 App 占用）、`Disconnected`（策略/物理断开）、`StorageInsufficient`；`Timeout` 增加 `cause`；降级链全失败时 `BindFailed.attempts` 保留真实尝试序号（`CameraBinder` 抛出即带 `attemptIndex`，门面的兜底包装只在非 `CameraError` 的意外异常时才回落为 0）。
- 新增 `CameraError.isRecoverable`（exhaustive `when`）：宿主可以据此决定"重试按钮"还是"去设置里授权"，不必自己维护错误清单。

### P4 变焦的线性位置

- `ZoomMath.ratioForFraction/fractionForRatio` 做**对数**映射：`1×` 在任何机型上都落在滑杆的同一位置，广角端不再"一动就跳"；
- 对外暴露 `setZoomFraction(0..1)`（带 clamp、范围退化时返回 `minRatio`）与 `state.linearZoom`（跟随程序化变焦与捏合手势）。

### P5 二进制稳定：`@JvmName` + `@JvmOverloads`

- 返回 `Result<Unit>` 的 6 个入口此前被 Kotlin 改写成 `capture-IoAF18A` 之类带值类后缀的 JVM 名，Java 反射拿不到、Java 宿主只能按混淆后的名字猜。现在显式 `@JvmName("capture")` 等。
- 三个配置类加 `@JvmOverloads`，Java 侧出现逐级默认参数重载。
- `CameraCoreApiContractTest` 用反射锁定公开面（生命周期/拍照/录像/变焦对焦/手电/预览/静态助手/派生属性/错误可恢复性），成员被删或改名先在这里红。

> 本批验证：`assembleDebug`、`testDebugUnitTest`（92 例）、`lintDebug` 全绿（`--offline`）。仍未做：AAR 的 `binary-compatibility-validator` API dump（需要联网拉插件，等 `M4` 一起做）。

---

## M1 稳定性（P1，一周内）

| ID | 问题 | 证据 | 改法要点 |
|---|---|---|---|
| S1 | `setFlashMode` 破坏 ZSL 不变量 | `CameraCore.kt:360-364` 只改 `imageCapture.flashMode`；`CaptureProfile.kt:45-48` 规定 ZSL 仅 `flashMode == OFF` 成立；`UseCaseFactory.kt:70` 已按 bind 时的 profile 设了 `CAPTURE_MODE_ZERO_SHUTTER_LAG` | 运行时把 flash 从 OFF 改走 → 立即把 `captureMode` 降为 `MINIMIZE_LATENCY`（或 `setZslDisabled(true)`）并重算 profile；否则下一次快门必失败而预览一切正常（最典型的"只有真机才复现"缺陷） |
| S2 | `CameraBinder` 的 use case 构建在 `try` 之外 | `CameraBinder.kt:74-108` 在 try(110) 外；抛出 → 逃出降级循环 → `CameraCore.kt:156` 包成 `BindFailed(0, …)` | 把构建挪进 `try`；`BindFailed(attempts, causes: List<Throwable>)` 保留每步原因 |
| S3 | `close()` 的 unbind 是死代码 | `CameraCore.kt:175-176` `mainExecutor.execute { provider?.unbindAll() }` 后立即 `provider = null` | 捕获局部变量：`val p = provider; mainExecutor.execute { p?.unbindAll() }`，或直接同步 `unbindAll()` |
| S4 | `SimpleLifecycleOwner` DESTROYED 后 `resume()` 抛异常 | `SimpleLifecycleOwner.kt:23` `createUnsafe`、`:33` 直接赋 `RESUMED` | 改走 `handleLifecycleEvent(ON_RESUME/ON_PAUSE/ON_DESTROY)`；DESTROYED 后一律 no-op；加主线程断言 |
| S5 | `FocusAfTracker.reset()` 不清基线 → 切镜头必等 2.5 s | `FocusAfTracker.kt:26-28` 只清 `sawScan`；`:44` `trustworthy = sawScan \|\| state != lastState`；`CameraCore.kt:223` controller 跨 rebind 复用 | 增 `resetBaseline()`：`lastState` 置 null 表示"无基线，首个终态须经 `sawScan`"；`attach()` 时调用 |
| S6 | 变焦 ticker 不感知相机态 | `ZoomController.kt:116-125` 只看 `model.isAnimating`；`detach()` 只 `cancel()` | 循环条件加 `control != null`；`detach()` 里 `submittedRatio = NaN` |
| S7 | `MediaStore` 发布不校验 | `GalleryPhotoSink.kt:101-104` `openOutputStream` 为 null 时静默跳过；`:106-110` `update` 返回值不检查就 `source.delete()` | null → 删行抛错；`updated != 1` → 删行；发布前校验 `source.length() > 0`；补 `WIDTH/HEIGHT/DATE_ADDED` |
| S8 | API<29 扫描异步 + `discard` 不重扫 | `GalleryPhotoSink.kt:149-154` 回调为 null；`:168-183` 删除不通知 scanner | 用 `OnScanCompletedListener` + suspend 等待并返回 `content://`；删除后重扫 |
| S9 | 录像音频异常被吞 | `VideoRecordingController.kt:90-97` 只 catch `SecurityException` 且静默降级 | 用 `PendingRecording.withAudioEnabled(AudioConfig.Builder().setAudioSource(MediaRecorder.AudioSource.CAMCORDER).build())`；无权限时发 `VideoFailed(AudioPermissionMissing)` 而不是无声录制 |
| S10 | 自动停止只在 `Status` 到达时判断 | `VideoRecordingController.kt:181-205` | `Status` 粒度约 1 s，`maxDuration`/`maxFileBytes` 会超调；改用 `withFileSizeLimit()`（MediaStore 输出）或用高频计时器兜一层 |
| S11 | `retryFailedSaves` 绕过配额 | `PhotoCaptureController.kt:194-200` 无 `tryAcquire`、无上限 | `take(maxPendingCaptures - budget.pending)` + 每项 `tryAcquire` + `attempts` 上限 |
| S12 | `close()` 后仍接受 `capture()` | `PhotoCaptureController.kt:87` 无 closed 标志 | `AtomicBoolean closed`，首行返回 `CaptureFailed(CameraClosed)` |
| S13 | 防抖能力"或"合并 → 绑定抛错 → **静默丢 video**（能预览、永远录不了） | `CameraCapabilitiesReader.kt:56-61,75` `stabilizationByCharacteristic \|\| stabilizationByRecorder`；`VideoOutputFactory.kt:70-72` 据此 `setVideoStabilizationEnabled(true)`；硬不支持时 `bindToLifecycle` 抛 `IllegalArgumentException`，被 `CameraBinder.kt:59-66` 第 3 步（去 video）吃掉 → 会话 Ready 但 `session.videoCapture == null`，`startRecording` 回 `NotReady`，`BindFailed` 还报告"降级成功" | 改 **AND**（characteristic && `Recorder.getVideoCapabilities().isStabilizationSupported`，Alibi 即如此）；只在 `enabled && supported` 时开；AUTO 降级发 `StabilizationDegraded` 事件而不是无声 |
| S14 | `AppPrivate` / 旧式相册目录不 `mkdirs` → **首段录像必失败且不自愈** | `VideoOutputFactory.kt:81-83` `File(filesDir, "camera-core/videos/…")` 无 mkdirs；`:86-93` Legacy 同（对照 `PhotoSink.kt:41`、`PhotoCaptureController.kt:103` 都建） | `allocate()` 内 `parentFile?.mkdirs()`；Legacy 补 `WRITE_EXTERNAL_STORAGE`/存储状态检查；API 24–28 优先走 `MediaStore` + `FileDescriptorOutputOptions` |
| S15 | 无 `RECORD_AUDIO` 自检：无声录制，或 `IllegalStateException` 穿透崩溃 | `VideoRecordingController.kt:89-97` 只 catch `SecurityException`；CameraX 随后 `checkState(recorder.isAudioSupported())` 抛 `IllegalStateException`，穿过 `CameraCore.kt:369-378`（无 try）；全库无 `checkSelfPermission` | 启动前 `ContextCompat.checkSelfPermission` + `recorder.isAudioSupported`；不可用则显式 `withAudioEnabled(false)` 并发 `VideoFailed(PermissionMissing(RECORD_AUDIO))` |
| S16 | `setTargetFrameRate(Range(fps, fps))` 硬钉固定档 → 同 S13 的静默丢 video | `VideoOutputFactory.kt:73-75`；CameraX 只接受 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 中精确存在的 range | 用 `Camera2CameraInfo.getCameraCharacteristic(CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)` 选包含目标值的 range，否则退回 `Range(30,30)`；`createRecorder` 的 `cameraInfo` 参数（`:40`）当前恒为 null（`CameraBinder.kt:102-107`），正好补上 |
| S17 | 开了防抖就无条件把画质压到 FHD 起 | `VideoOutputFactory.kt:47-57` `QualitySelector.fromOrderedList(FHD, HD, SD, lowerQualityOrHigherThan(SD))`，**丢弃 `decision.quality`**；`VideoStabilizationPolicy.kt:38-54` 已算好的 SD/HD 被无视 | policy 直接产出 selector：显式 quality 优先 + `lowerQualityOrHigherThan` 回退；只有 UHD/HIGHEST 才收进包络（对标 Alibi `shouldUseStabilizedAutoQuality`） |
| S18 | 无 `moov` 保护、无分段：进程被杀/断电 = 整段报废 | `VideoOutputFactory.kt:44-60` 未设任何 limit；CameraX 用 `MediaMuxer` 直写，`moov` 只在 finalize 追加 → 非正常终止留下只有 `mdat` 的文件（`ffprobe: moov atom not found`），且 `IS_PENDING=1` 连"可见但坏"都不是 | ① 分段：`setDurationLimitMillis(120_000)`，`ERROR_DURATION_LIMIT_REACHED` 视为成功并自动开下一段（对标 Alibi `IntervalRecorderService`）；② 启动时用 `MediaExtractor` + `MediaMuxer` 重封装残片（复制不重编码，顺带 `setOrientationHint` 解决 S19）；③ 升级 1.6.x 的 Media3 muxer（见 C1） |
| S19 | 录制中旋转无解：整段视频歪 90°，文档承诺"未实现" | `VideoOutputFactory.kt:63-64` `setTargetRotation` 只在 bind 时定；`CameraCore.kt:407-411` `updateRotation()` 只改 `imageCapture`；`VideoRecordingController.kt:45` `rotationSource` 参数 `@Suppress("unused")`；`MediaStoreOutput`（`:159-200`）不写 `ORIENTATION`（Q+ 该列只读） | ① `start()` 前用 `rotationSource.currentRotation()` 设 `videoCapture.targetRotation`，并在录制中让 `updateRotation()` 显式 no-op 而不是静默错改；② 停录后用 `MediaExtractor`+`MediaMuxer` 复制 sample + `setOrientationHint` 修正（S18 的修复器复用同一段代码）；③ 把实际 rotation 暴露给宿主做播放层旋转 |

---

## M2 速度（P1，两周内，按收益排序）

### B1 扩展解析惰性化 —— 每次 open 白付 120–400 ms（最坏 3 s）

- **证据**：`CameraCore.kt:197` `resolver.availableModes(...)` 在 `extension = OFF` 时也执行；`ExtensionsResolver.kt:36`/`:52` 各取一次 manager；`:72-76` `ExtensionsManager.getInstanceAsync(...).awaitValue(3000)`；`resolve()` 的 OFF 短路（`:48-50`）在 `availableModes` 之后。全部跑在 `Dispatchers.Main.immediate`（`CameraCore.kt:64`）。
- **改法**：`if (config.extension == OFF) { available = emptySet(); mode = OFF }`，`availableModes()` 移入 `resolve()` 且只在非 OFF 时调用；`(cameraId, lens)` 结果进程级缓存；manager 单例复用。整段 `withContext(Dispatchers.Default)`，bind 前回主线程。
- **验收**：默认配置（OFF）下 `open()` 不再触发任何 extensions binder 调用（打点断言）。

### B2 预热 `ProcessCameraProvider` + 限制可用相机

- **证据**：`CameraCore.kt:182-184` 每次 `open()` 都 `getInstance(appContext).awaitValue(5000)`（CameraX 内部是进程级 future，第二次≈0，但**首次** 300–700 ms 落在用户点击之后）。
- **改法**：宿主 `Application` 实现 `CameraXConfig.Provider`，配 `setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)`（本库只用前/后摄；忽略无关摄像头可显著降低首次枚举成本）+ `setMinimumLoggingLevel`；提供 `CameraCore.warmUp(context)`（`Dispatchers.Default` 里 `ProcessCameraProvider.getInstance` + `availableCameraInfos`），建议宿主在 `onCreate` 调。
- **验收**：冷启 `open()` p50 下降 ≥50%；`warmUp` 之后首次 `open()` 无 provider 初始化打点。

### B3 重绑不要 `unbindAll()` —— 每次 +150–400 ms

- **证据**：`CameraCore.kt:144` 先 `unbindAll()` 再 bind；`unbindAll` 会让 CameraX 关闭 camera device，于是每次切镜头/改配置都多付一次 open + 一次 3A 收敛。
- **改法**：同一 selector + 同一 use case 集合 → 直接 `bindToLifecycle` 覆盖（CameraX 支持替换，不关设备）；确需清场用 `provider.unbind(preview, imageCapture, …)`。
- **验收**：`switchLens` p95 ≤200 ms；切镜头后首帧不黑屏。

### B4 能力读取进程级缓存 + 移出主线程

- **证据**：`CameraCapabilitiesReader.kt:50-54`/`:56-61`/`:72` 每次 bind 重算；`Recorder.getVideoCapabilities(cameraInfo)` 30–150 ms；`CameraCore.kt:199` `getCameraInfo` 是阻塞调用（10–80 ms）；`:201`/`:210` 还各算一次 `availableLenses` 与 `frameRateRanges`。
- **改法**：`ConcurrentHashMap<cameraId, CameraCapabilities>` 缓存（这些量是设备常量）；`getCameraInfo` + 能力读取挪到 `Dispatchers.Default`。
- **验收**：第二次 `open()` 的能力读取耗时 ≈0。

### B5 `open()` 加总超时；异步段可观测

- **证据**：`openTimeoutMillis` 只包 `:183` 的 provider future；`CameraBinder.kt:111` `bindToLifecycle` 无 deadline，设备卡在 surface 组合时 `open()` 永不返回。
- **改法**：`withTimeout(config.openTimeoutMillis) { bindSession(...) }`（bind 不可取消，但至少返回 `CameraError.Timeout("bind", ms)` 而不是挂死）；`BindFailed` 记每步耗时，`debugLogging` 下打出。

### B6 用能力预判替代"4 步试错"

- **证据**：`CameraBinder.kt:59-66` 四步只减 use case 数量，**从不降分辨率**（`:81-90` 四步共用同一 `imageCapture`）；第 4 步仅放松 preview。`UseCaseFactory.kt:139` `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` 会把 4032×3024 的目标**向上**选到 8000×6000/12000×9000（48/108 MP 传感器），内存与编码时间暴涨。
- **改法**：bind 前按 `INFO_SUPPORTED_HARDWARE_LEVEL` + `SCALER_STREAM_CONFIGURATION_MAP` 选组合（LEGACY 直接 3 路、不绑 analysis）；目标尺寸从 `getOutputSizes(JPEG)` 里挑真实存在的 ≤12 MP 4:3 尺寸，用 `CLOSEST_LOWER_THEN_HIGHER`；第 4 步改成"降 JPEG 到 8 MP"而不是只降 preview。（`availableConcurrentCameraInfos` 回答的是"多摄能否同时开"，与本问题无关，不要用它预判。）
- **验收**：中低端机首绑失败次数 = 0；高像素机拍出的 JPEG 不超过配置的目标像素。

### B7 journal IO 与保存链移出主线程

- **证据**：`PhotoCaptureController.kt:104` 在**快门按下前**同步 `journal.record`（一次全量读 + 解析 N 行 + 全量重写）；`:131-133` `takePicture(..., mainExecutor, ...)` 回调在主线程；`:206` `markSaving` 又一次全量重写；`PendingPhotoJournal.kt:34-37`。连拍时 O(n²)。
- **改法**：`takePicture(options, ioExecutor, cb)`（或回调里只 `scope.launch(Dispatchers.IO)`）；`markSaving` 直接删除（`Stage.SAVING` 无任何读取点）；journal 改 append-only（`FileOutputStream(file, true)` + 定期 compaction）+ 内存镜像，`load()` 只在启动时调一次；写路径加 `fd.sync()`（见 S-journal）。
- **验收**：单张快门主线程占用 ≤2 ms（StrictMode 无 `diskWrite` 命中）；连拍 20 张的总吞吐提升 ≥2×。

### B8 MediaStore 直写，砍掉 `filesDir → /sdcard` 全量拷贝

- **证据**：`GalleryPhotoSink.kt:90-110` 先把 JPEG 写进 `filesDir/pending`，再 `insert(IS_PENDING=1)` → `copyTo` → `update(IS_PENDING=0)`；一张 4–8 MB 的图等于写两遍。
- **改法**：`resolver.insert(... IS_PENDING=1)` 拿 uri → `ImageCapture.OutputFileOptions.Builder(resolver, uri, values)` 直接交给 `takePicture` → `onImageSaved` 里 `update(IS_PENDING=0)`，失败 `delete(uri)`。CameraX `ImageSaver` 已经处理好 `ERROR_FILE_IO` 与流关闭。journal 记录 uri 而非 path。
- **验收**：快门 → `PhotoSaved` p95 下降 ≥40%；`/sdcard` 写入量减半。

### B9 postview：按快门即出图

- **证据**：全库 `setPostviewEnabled` 0 命中；`PhotoCaptureController.kt:131-143` 只有 `OnImageSavedCallback`，而它的回调时机是"JPEG 完整落盘 + EXIF 写完"。
- **改法**：`ImageCapture.Builder().setPostviewEnabled(true).setPostviewResolutionSelector(…1280×960, CLOSEST_HIGHER_THEN_LOWER…)`；用 `takePicture(executor, OnImageCapturedCallback)` 拿 `onPostviewBitmapAvailable(Bitmap)`，立刻发 `PhotoState.PostviewReady`，最终 `PhotoSaved` 到达后替换。`onCaptureProcessProgressed(int)` 可驱动进度。
- **验收**：快门 → 缩略图 p95 ≤150 ms。

### B10 变焦跟手性三连修

- **证据**：`ZoomController.kt:118` `delay(16)`（非 vsync，120 Hz 屏上限 62.5 Hz）；`ZoomDispatcher.kt:83` 看门狗 150 ms，短于中低端机 `setZoomRatio` 的 p99（100–300 ms），导致"每个请求都判失败而硬件仍在顺序执行"；`ZoomController.kt:144` `submittedRatio` 提前推进 → 一次超时该比例永久静默失配；`grep zoomState` 只在 `CameraCapabilitiesReader.kt:35` 读一次，**从不回读设备真值**。
- **改法**：① ticker 改 `withFrameNanos`/`Choreographer.postFrameCallback`（vsync 对齐 + 跟随刷新率，`dt` 仍 `coerceIn(1f, 64f)`）；② 看门狗改 600 ms 且语义改为"延迟监控"而非"失败判定"（内层 `APPLY_TIMEOUT_MILLIS=2000` 是死代码，删）；③ `submittedRatio` 只在成功回调推进，失败置 `NaN` 并指数退避重试；④ 订阅 `cameraInfo.zoomState`（LiveData）作为 `state.zoomRatio` 的唯一权威；⑤ 动画域改用 `linearZoom` + `setLinearZoom`（`zoomRatio` 上等步长的视场变化非线性，导致广角端偏快、多摄切换跳变）。
- **验收**：60/120 Hz 上 pinch 视觉帧率 = 屏刷新率；`zoomState.zoomRatio` 与 `state.zoomRatio` 的偏差 ≤1%；连续 50 次变焦无一次超时。

### B11 旋转链路补齐

- **证据**：`CameraCore.kt:409-411` 只更新 `ImageCapture.targetRotation`；`WindowManagerRotationSource.kt:16-21` 用 `applicationContext` 的 `defaultDisplay`（API 30+ 多窗口/折叠屏错）；无 `DisplayListener`，只能靠宿主轮询 `updateRotation()`。
- **改法**：`updateRotation()` 同时设 `preview.targetRotation` + `imageCapture.targetRotation` + `videoCapture?.targetRotation`（1.5 允许运行时改）；旋转源改宿主 UI context 的 `display.rotation`（不可得时退回 `WindowMetrics`）；库内注册 `DisplayManager.DisplayListener` 并在 `close()` 注销；折叠屏可接 `WindowInfoTracker`。
- **验收**：横竖屏切换后取景方向与成片 EXIF 一致；录像中旋转不需要重绑。

---

## M3 能力与画质（P1–P2，一个月内）

### C1 升级 CameraX 1.5.3 → 1.6.2（本项收益最大且几乎零代码）

- **依据**：官方 release notes（2026-03-25 的 1.6.0、当前 stable 1.6.2；RC 线已到 1.7.0-rc01）。
- **能白拿的四件事**：
  1. **CameraPipe** 统一高性能栈（与 Pixel 相机同栈）——设备支持时自动更快的管线；
  2. **`VideoCapture` 默认集成 Media3 muxer** —— 修掉"进程被杀/意外中断导致 MP4 不可播"与 proxy FD 保存崩溃。对标车机场景这是**最关键的一条**；
  3. **Feature Group API**（`VIDEO_STABILIZATION` / `UHD_RECORDING` / `PREVIEW_STABILIZATION` / HDR / 60FPS）+ `isSessionConfigSupported` 预检 —— 直接替换 B6 的试错降级；
  4. **`SessionConfig` 转正**（含 `HighSpeedVideoSessionConfig`、`ExtensionSessionConfig`），`ExtensionsManager.getInstance` 挂起版。
- **迁移注意**：minSdk 默认 23（本项目 24，无碍）；1.5.3 已含 Android 17(API 37) dynamic-range 崩溃修复；`compileSdk 36` 足够。
- **验收**：录像中 `adb shell am force-stop <pkg>` 后 MP4 仍可播（或可被 `MediaExtractor` 打开）；现有 57 个单测 + lint 全绿。

### C2 用 `SessionConfig` / Feature Group 做"预检 + 一绑成功"

- **改法**：bind 前 `provider.isSessionConfigSupported(config)`（例如 `VIDEO_STABILIZATION + UHD + 60fps` 组合），不支持就直接走降质策略并**告知宿主**（`CameraCapabilities` 里暴露"最终生效组合"），不再靠 4 步 bind 试错。
- **验收**：日志里每次 `open()` 只有一次 `bindToLifecycle`（当前最坏 4 次）。

### C3 照片：尺寸真值、镜像、HEIF/UltraHDR 预研

- **尺寸真值**：bind 后读 `imageCapture.resolutionInfo?.resolution`，用它做预算估算、`fitsHardwareJpeg` 判断和 `SavedPhoto` 元数据（现在 `CameraBinder.kt:140-148` 的 `clampToHardware` 结果只用于一个布尔，实际下发尺寸仍是硬编码 4032×3024）。
- **镜像**：用 `setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)` 替代手写 `Metadata.isReversedHorizontal`（`PhotoCaptureController.kt:121-125`），少一次元数据分叉。
- **HEIF/UltraHDR**：1.6/1.7 已支持可查询的动态范围 profile；建议先做能力上报（`CameraCapabilities` 增 `dynamicRangeProfiles`），等宿主需求再启用拍摄。
- **ZSL 保持**：现有 `JpegPolicy.captureMode` 逻辑正确，唯一缺口是运行时 `setFlashMode` 破坏不变量（S1）。

### C4 视频：音频参数、编码格式、分段续录

- **音频**：当前没有 `AudioConfig`（`VideoRecordingController.kt:90-97`），默认走 MIC + 系统 AGC。行车场景建议 `MediaRecorder.AudioSource.CAMCORDER`（或 `UNPROCESSED`，若设备支持）以保留原始声压；1.7 后可调 `setTargetAudioChannelCount` / `setTargetAudioEncodingBitRate`。
- **编码格式**：1.7 起 `Recorder.getSupportedVideoMimeTypes()` / `setVideoMimeType()`；HEVC 在同码率下更清晰（但兼容性/解码开销需宿主可选）。
- **分段续录（loop recording）**：当前只有 `maxDurationMillis` / `maxFileBytes` 到点**停止**（`VideoRecordingController.kt:189-204`），命中即 `stop()`。行车记录仪的核心语义是"到点封一段、立刻开下一段"，建议在 `VideoConfig` 增 `segmentDurationMillis` / `segmentBytes`，由控制器在 `Finalize` 成功后自动 `start()` 下一段（并把上一段的 uri 连续发出），媒体索引由宿主维护。这是本库从"相机库"变成"行车记录仪内核"的关键一步，也是与 Alibi 上游最大的功能差异点。
- **验收**：设定 1 GB 分段，连续录 2 h，产生 ~N 段可播文件，段间丢帧 ≤1 帧。

### C5 `UseCaseGroup` + `ViewPort`：所见即所得

- **证据**：`CameraBinder.kt:110-111` 直接 `bindToLifecycle(…, *useCases)`，无 `UseCaseGroup`。分别选 crop 的 `Preview` 与 `ImageCapture` 在部分机型上 FOV 不一致（构图 ≠ 成片）。
- **改法**：`UseCaseGroup.Builder().addUseCase(preview).addUseCase(imageCapture).addUseCase(videoCapture).setViewPort(hostViewPort)`；`CameraCore` 增 `fun setViewPort(width, height, rotation)` 由宿主传入 `PreviewView.viewPort`。顺带减少 surface 组合搜索空间（bind 更快）。
- **验收**：预览框内的边缘与成片边缘像素级一致（±1%）。

### C6 逻辑相机与物理镜头

- **现状**：`CameraLens` 只有 `BACK/FRONT`（`Config.kt:15-22`），注释明确不做物理多摄，靠 logical multicam zoom 覆盖；未使用 `availableCameraInfos`。
- **改法**：短期在 `CameraCapabilities` 暴露 `physicalCameraInfos`（若有）与 `sensorRotationDegrees`；CameraX 1.7 支持 `getPhysicalCameraInfos()` + 物理相机 selector 后，增 `CameraCoreConfig.physicalCameraId: String?`（超广角/长焦直选）。另建议 `CameraLens` 增 `EXTERNAL`（USB/UVC，API 28+，运行时判定）。
- **验收**：宿主能列出"可用的逻辑/物理相机"并直连其中一台。

---

## M4 工程化与可观测（贯穿，持续）

| ID | 问题 | 现状 | 改法 |
|---|---|---|---|
| D1 | 没有版本控制 | `git log` → `fatal: not a git repository`（有 `.gitignore` 无 `.git`） | 建仓 + 打 tag，`docs/EXTRACTION-MAP.md` 作为首个 commit 的溯源说明 |
| D2 | 没有 CI | 无 `.github/` | GitHub Actions：`assembleRelease` + `testDebugUnitTest` + `lint`（当前 57 单测/0 lint 是人工结论，应自动守护） |
| D3 | 没有 API 兼容性护栏 | 无 `binary-compatibility-validator`、无 api dump | 加 `apiValidation {}` 生成 `api/camera-core.api`，CI 校验；`CameraCoreConfig` 等 data class 的构造签名即二进制 ABI，加字段就是 breaking |
| D4 | 零日志 | `grep android.util.Log` → 0 命中；`runCatching{}.getOrNull()` 吞掉所有能力探测失败 | `CameraCoreConfig.debugLogging: Boolean` + 内部 `Logger` 接口；`runCatching` 改 `onFailure { log.w(...) }`；能力降级要能被宿主看到 |
| D5 | 中间态放在 `filesDir` | `PhotoSink.kt:41`、`PendingPhotoJournal.kt:134`、`VideoOutputFactory.kt:82`（AppPrivate 录像也进 `filesDir`） | 中间态全搬 `noBackupFilesDir`：Auto Backup 会把旧设备的 `.part`/journal 恢复到新设备，`recoverPending()` 会把旧照片塞进新相册（隐私事件）；AppPrivate 录像同理 |
| D6 | 错误类型不完整 | `CameraError.kt` 缺 in-use/disconnected/provider-init；`Timeout` 无 `cause`；`BindFailed(attempts=0)` 语义错 | 增 `CameraInUse` / `Disconnected` / `ProviderInitFailed` / `SessionLost` / `ExtensionFailed`；`Timeout(operation, millis, cause)`；`BindFailed(attempts, causes)` |
| D7 | Manifest 强制给宿主加权限 | `AndroidManifest.xml:8-9` 声明 `CAMERA` + `RECORD_AUDIO`（还有 `WRITE/READ_EXTERNAL_STORAGE`），会被 manifest merger 提升进宿主 | 库只保留 `uses-feature`（`camera.any` / `camera.autofocus` / `microphone`，全部 `required=false`），权限由宿主按需声明 |
| D8 | 测试覆盖偏向纯函数 | 10 个单测文件都在测可测逻辑（SmoothZoom/ZoomDispatcher/FocusAfTracker/journal/budget/profile/naming）；`PhotoCaptureController`（288 行）与 `VideoRecordingController`（292 行）**零测试** | 抽 `PhotoCaptureGateway` / `RecordingGateway` 接口把 `ImageCapture`/`VideoCapture`（final 类，无法 mock）包一层，用 fake 覆盖本文 A4/A5/A6/S1/S10 的回归 |
| D9 | 无性能基准 | 无打点 | 加 `CameraTimings`：`providerReady / extensionsResolved / capabilitiesRead / bound / firstFrame / shutterToPostview / shutterToSaved`，`debugLogging` 下以事件发出；配 Macrobenchmark 或真机脚本跑 p50/p95 |

---

## 5. 明确不建议做的事

1. **不要为了"快"把 JPEG 质量推到 100**：`JpegPolicy` 里 `QUALITY_PRIORITY = 100` 在多数编码器上被 clamp，只增 30–50% 体积与 MediaStore 写入时间，没有任何画质收益，建议 98。
2. **不要退化成 Camera2 手写管线**：CameraX 1.6 的 CameraPipe 已经是 Pixel 相机同栈，自己写的 session 组合很难超过它，且会丢掉 1.6 的 muxer 兜底。
3. **不要回到"切模式就重绑"**：现有单会话多 use case 是本库相对 Alibi 的核心优势，B3 的正确方向是"重绑也别关设备"，不是"频繁重绑"。
4. **不要在库里做 UI**（本轮确认范围）：postview 只发 `Bitmap` 事件，不碰 `ImageView`；`ViewPort` 由宿主传入尺寸。
5. **不要用 `availableConcurrentCameraInfos` 预判单相机多路流**：它回答的是"多个相机能否同时打开"，与 surface 组合无关。

---

## 6. 建议排期

| 阶段 | 内容 | 工作量 | 退出标准 |
|---|---|---|---|
| **M0**（1–2 天） | A1–A6 | ~200 行 | 抢占/磁盘满/rebind 三类事故有回归测试 |
| **M1**（1 周） | S1–S12 | ~300 行 | lint 0 error；单测 ≥80 个；真机冒烟无崩溃 |
| **M2**（2 周） | B1–B11 | ~600 行 | `open()` p50 ≤300 ms；快门→缩略图 p95 ≤150 ms；pinch 满帧 |
| **M3**（1 个月） | C1–C6 | ~800 行 + 依赖升级 | 2 h 录像可播；一次 bind 成功；所见即所得 |
| **M4**（持续） | D1–D9 | — | CI 绿、api dump 稳定、有真机 p50/p95 报表 |

依赖顺序：C1（升级 CameraX）建议在 M2 之前完成——升级本身零代码，却能同时改善启动（CameraPipe）、录像可靠性（muxer）与降级策略（Feature Group），并让 B6 的改法有官方 API 支撑。

---

## 附录 A：本文引用的一手证据

- `camera-core/src/main/java/com/recordingcamera/core/CameraCore.kt:122-160, 182-297, 360-365, 409-411, 428-439`
- `.../internal/CameraBinder.kt:59-131, 140-149`；`.../internal/UseCaseFactory.kt:30-141`；`.../internal/ExtensionsResolver.kt:32-90`；`.../internal/CameraCapabilitiesReader.kt:35-91`
- `.../photo/PhotoCaptureController.kt:87-152, 158-191, 194-221, 242`；`.../photo/JpegPolicy.kt:15-34`；`.../photo/PendingCaptureBudget.kt:23-39`；`.../photo/PhotoTargetSize.kt:31-45`
- `.../storage/PendingPhotoJournal.kt:30-81, 108-109, 133-134`；`.../storage/GalleryPhotoSink.kt:90-115, 149-183, 194-203`；`.../storage/DocumentTreePhotoSink.kt:37-118`；`.../storage/PhotoSink.kt:40-52`
- `.../video/VideoRecordingController.kt:58-164, 181-205, 222-292`；`.../video/VideoOutputFactory.kt:39-113, 126-208`；`.../video/VideoStabilizationPolicy.kt`
- `.../zoom/ZoomController.kt:116-167`；`.../zoom/ZoomDispatcher.kt:48-83`；`.../focus/FocusController.kt:38-174`；`.../focus/FocusAfTracker.kt:17-44`；`.../util/Futures.kt:21-41`；`.../util/SimpleLifecycleOwner.kt:23-44`；`.../util/WindowManagerRotationSource.kt:16-24`；`.../util/MediaNaming.kt:41-44`
- `camera-core/src/main/AndroidManifest.xml:4-17`

## 附录 B：外部依据

- CameraX release notes（1.6.0 / 1.6.2 / 1.7.0-rc01）：CameraPipe、Media3 muxer、Feature Group、SessionConfig、`ExtensionsManager` 挂起版
- CameraX 配置文档：`CameraXConfig.setAvailableCamerasLimiter`、`UseCaseGroup` + `ViewPort`、`ImageCapture.targetRotation` 运行时可改、`CameraInfo.getZoomState()` 为 LiveData、postview（`setPostviewEnabled` / `onPostviewBitmapAvailable`）
- `github.com/android/camera-samples`、`github.com/GrapheneOS/Camera`、AOSP `Camera2Video`、AndroidX Media3
