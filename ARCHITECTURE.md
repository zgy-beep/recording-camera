# 架构与设计决策

本文件记录 `camera-core` 的实现结构与**为什么这样做**。改代码前先读这里，尤其是“不变量”一节。

## 1. 线程与协程模型

| 执行者 | 用途 |
| --- | --- |
| `Dispatchers.Main.immediate`（`CameraCore.scope`） | 状态流转、控制器编排、缩放 ticker、对焦超时 |
| `ContextCompat.getMainExecutor` | `takePicture` 回调、`Recording.start` 事件、Future 监听 |
| `Dispatchers.IO` | 照片落盘 worker、相册/SAF 写入、日志读写 |
| CameraX 内部线程 | Camera2 会话与图像管线 |

约定：

- **所有 `CameraCoreState` 变更都在主线程**（`MutableStateFlow.update`），因此宿主可以直接读状态渲染。
- 阻塞 IO（`openAssetFileDescriptor`、`File.renameTo`、`DocumentFile` 查询）只在 IO 线程出现，主线程不做同步存储访问。
- Controller 都持有 `scope`，**不自己创建 scope**：`close()` 时统一 `scope.cancel()`，避免泄漏。
- `ListenableFuture` 通过 `util/Futures.kt` 的 `awaitValue(timeout, operation)` 转为挂起调用，超时抛 `CameraError.Timeout`；`onResult` 用于必须回主线程的轻量回调。

## 2. 会话生命周期

```
IDLE ──open()──► OPENING ──bind 成功──► READY ──close()──► CLOSED
                    │                     │
                    └──失败──► FAILED ◄───┘（绑定/重绑失败）
```

`open()` 的固定顺序（顺序本身就是契约）：

1. 若正在录像 → `InvalidState`（重绑会杀掉 `Recording`）。
2. 记录 `state.zoomRatio`（后面重绑要恢复）。
3. `detachControllers()` + `provider.unbindAll()`：先释放旧会话，CameraX 不允许同一 owner 二次绑定。
4. `ProcessCameraProvider.getInstance().awaitValue(openTimeoutMillis)`。
5. `bindSession()`：能力读取 → 扩展解析 → 拍摄参数 → 防抖决策 → 绑定 → 控制器装配 → 状态置 `READY`。

`bindSession()` 中控制器创建顺序有意固定：`FocusController` 必须在 `binder.bind()` **之前**创建，因为它的 Camera2 会话回调要装到 Preview 用例上，晚一步就拿不到 AF_STATE。

`detachControllers()` 会 `close()` 而不是丢弃引用：`PhotoCaptureController` 的保存 worker 必须被关闭，否则每次重绑泄漏一个协程（已修复的缺陷）。

## 3. 绑定降级链

`internal/CameraBinder.kt` 依次尝试：

1. Preview + ImageCapture + VideoCapture + ImageAnalysis
2. 去掉 ImageAnalysis
3. 再去掉 VideoCapture
4. 再去掉分辨率选择器（用 CameraX 默认预览尺寸）

每一步都**重新构建用例实例**（CameraX 用例不能跨失败重试复用），失败时 `unbindAll()` 清场。全部失败抛 `BindFailed(attempts, cause)`。
`BoundSession.useCases` 会如实反映最终绑定成功的那一组，宿主据此禁用不可用功能（`state.availableUseCases`）。

ImageAnalysis 的作用不是识别，而是**让非 ZSL 管线持续有请求在跑**，否则部分机型 AE/AF 会停滞。所以它由 `keepAnalysisStreamForAutofocus` 与扩展能力共同控制。

## 4. 能力读取与拍摄参数

`CameraCapabilitiesReader` 通过 `Camera2CameraInfo.getCameraCharacteristic` 直读 Camera2 特征（全部 `runCatching`，缺失即 `null`），产出 `CameraCapabilities`：

- `min/maxZoomRatio` ← `cameraInfo.zoomState`；`intrinsicZoomRatio` ← `intrinsicZoomRatio`（未知按 1x）
- `maxHardwareJpegPixels` ← `SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(JPEG)` 的最大像素数
- `opticalStabilizationSupported` / `continuousAutoFocusSupported` / `videoStabilizationSupported` ← 对应 `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`、`CONTROL_AF_AVAILABLE_MODES`、`CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`

`CaptureProfileFactory.build()` 把用户意图（速度/均衡/画质、闪光、扩展）翻译成一个不可变的 `CaptureProfile`：

- ZSL 只在 `flash == OFF && isZslSupported && !qualityPriority && !extensionActive` 时开启（带闪光或扩展的 ZSL 不成立）。
- 速度模式才启用 OIS/高帧率；画质模式才要 HQ 降噪/锐化并禁用高帧率（两者互斥，这是硬件的真实约束）。
- `selectFrameRate()`：先过滤 `lower >= 30`，再按 `|upper - 目标|`、区间宽度、上限降序挑选 —— 同为 60fps 时优先**窄区间**（`60..60` 胜过 `30..60`），否则 AE 会在暗光下落回 30fps。

`internal/UseCaseFactory.kt` 把这些翻译成 CameraX builder 调用；只有 `!extensionActive` 时才挂 `Camera2Interop` 的捕获请求选项（扩展会用厂商配置覆盖这些值）。

## 5. 拍照管线

```
capture() → 配额 → 命名 → 临时文件 .part → 日志(CAPTURED)
        → takePicture → onImageSaved → 日志(SAVING) → 队列 → 单 worker → PhotoSink.store()
        → 日志 remove → 事件 PhotoSaved / RecoveredPhoto
```

不变量：

- **先记账再拍**。日志写在 `takePicture` 之前，进程被杀后 `recoverPending()` 能把临时文件重新投递；顺序反过来必然丢照片。
- **配额先占后放**。`PendingCaptureBudget.tryAcquire()` 失败直接返回 `QueueSaturated`，绝不“先拍了再丢”。
- **保存严格串行**。`Channel(UNLIMITED)` + 单 worker：并发写 MediaStore 会打乱相册顺序，也会让 `IS_PENDING` 交错。
- **失败保留证据**。保存失败时临时文件与日志记录都保留，`retryFailedPhotoSaves()` 可重试；只有成功才 `journal.remove()`。
- 日志格式是 6 列 TSV（id/临时路径/显示名/时间戳/镜头/阶段），带 `\\ \t \n` 转义；写入用 `.tmp` + 重命名，读到坏行整体丢弃（宁可丢记录也不要卡住启动）。

## 6. 变焦管线

```
宿主输入 ─┬─ zoomBy(scale)  → SmoothZoom.scaleBy  → 缓动
          └─ setZoomRatio(r) → SmoothZoom.snapTo   → 立即
                                     │
                          16 ms ticker (advance)
                                     │
                      变化 > submitEpsilon 才提交
                                     ▼
                    ZoomDispatcher: Channel(CONFLATED) → 单次在途 setZoomRatio
```

- `SmoothZoom` 用 `1 - exp(-dt/τ)`（τ=60 ms），**帧率无关**：10×16 ms 与 5×32 ms 落点一致，已由单测锁定。`dt` 上限 64 ms，防止卡顿后瞬移。
- `ZoomDispatcher` 取代原来的 32 ms 时间戳队列：`CONFLATED` 保证只保留最新值，`withTimeoutOrNull(150)` 保证硬件 Future 悬挂时 worker 也能继续。超时只计数（`timedOutCount`）不抛错——变焦不能因为一次挂起就中断。
- 只有绝对输入（滑杆、按钮、恢复上次倍率）允许 `snapTo`；相对输入（捏合）必须缓动，否则手感是“跳变”。
- 状态发布有 `publishEpsilon = 0.002` 的死区，避免每帧都触发宿主重组。

## 7. 对焦管线

`FocusController.focusAt(x, y, size)`：

1. 分配 `generation++`，取消上一次的 `CompletableDeferred` 与超时任务（旧请求结果直接作废）。
2. `cancelFocusAndMetering()` 后再 `startFocusAndMetering(...)`：连续两次对焦若不取消，部分机型会排队。
3. `FocusMeteringAction` 用 `FLAG_AF | FLAG_AE`，`setAutoCancelDuration(3 s)`。
4. 三条完成路径：CameraX Future 的 `isFocusSuccessful`、Camera2 `CONTROL_AF_STATE` 回调、2.5 s 超时。

`FocusAfTracker` 是这段的防误报核心：只有当**出现过扫描态**（`PASSIVE_SCAN=1` / `ACTIVE_SCAN=3`）或**状态相对上次发生了变化**时，`FOCUSED_LOCKED=4` 才被采信。恒定焦点的固定对焦机型会一直上报 `FOCUSED_LOCKED`，没有这层校验就会出现“点了哪里都显示对焦成功”。
`reset()` 只清 `sawScan`，**不清基线 `lastState`**：驱动会重放终态，清掉基线等于放弃比较。

`FocusController.captureCallback` 通过 `Camera2Interop.Extender.setSessionCaptureCallback` 挂在 Preview 用例上——这是纯 CameraX 下拿到 `AF_STATE` 的唯一途径。

## 8. 录像管线

```
start() → 决策校验 → allocate 输出 → prepareRecording → withAudioEnabled → Preparing
        → start(executor) → Start/Status/Pause/Resume 事件 → Recording
        → stop() → Finalizing → Finalize → 落库/清理 → VideoSaved
```

- `VideoStabilizationPolicy`：防抖只在设备支持时开启；一旦开启并请求 `HIGHEST/UHD`，画质被压到 **FHD**、帧率被压到 **30**（超出防抖工作包络的组合基本都会被驱动静默丢弃防抖）。请求 `REQUIRE` 而设备不支持时，`Decision.unsatisfied = true`，`startRecording()` 返回 `Unsupported` 而不是假装开了防抖。
- 防抖开启时 Recorder 使用 `FHD/HD/SD` 的降级列表（`lowerQualityOrHigherThan`），保证所选画质落在包络内。
- 输出分配有三种实现（私有文件 / 相册 / SAF），统一为 `AllocatedVideoOutput`：`options` + `displayName` + `expectedUri` + `finalize(success, resolvedUri)`。`finalize` 负责失败时删行/删文档、成功时把 `IS_PENDING` 置 0 或触发媒体扫描 —— 这条“善后”逻辑只写一遍。
- `stop()` 之后必须等 `Finalize`：文件还没封装完就读会拿到坏 mp4。15 s 看门狗兜底，避免服务永久卡在 `Finalizing`。
- 时长/文件上限由 `Status` 事件里的 `recordedDurationNanos` / `numBytesRecorded` 驱动，触发后自动 `stop()` 并补发 `VideoAutoStopped(reason)`。

## 9. 存储抽象

`PhotoSink` 只有一个方法 `store(source, displayName, lens, capturedAtMillis)`。三种实现：

- `InternalPhotoSink`：`filesDir` 内 `renameTo`（同分区原子），返回 `file://` 而非 `content://`，宿主需要自己配 `FileProvider`。
- `GalleryPhotoSink`：API 29+ 走 MediaStore（`RELATIVE_PATH` + `IS_PENDING` 1→0）；更早版本写 `Environment.getExternalStorageDirectory()` 后 `MediaScannerConnection.scanFile`。
- `DocumentTreePhotoSink`：只用 `DocumentsContract`（不引 `documentfile` 依赖），在 tree 下找/建子目录再建文档。

尺寸通过 `BitmapFactory` 的 `inJustDecodeBounds` 读取（不解码像素），视频大小/SavedPhoto 字节数来自实际写入结果的 `openAssetFileDescriptor().length`。

## 10. 状态与事件

- 一个 `CameraCoreState`（不可变 data class）：`status/lens/capabilities/availableUseCases/zoomRatio/zoomRatioRange/focus/flashMode/torchEnabled/activeExtension/photo/video/lastPhoto/lastVideo/error`。
- 控制器拿到的是 `CoreStateSink`（`current / update / emit`），不是 `CameraCore` —— 这样控制器之间不互相引用，也便于单测。
- `lastPhoto/lastVideo` 在 `emit` 时顺带写入状态：`SharedFlow` 不重放，宿主界面重建后仍能拿到最近一次成品。
- 变焦的窄更新（只改 `zoomRatio`）是刻意的：全量 `publish()` 会让每一帧重组整个画面。

## 11. 测试地图（57 个 JVM 单测）

| 文件 | 锁定的行为 |
| --- | --- |
| `zoom/SmoothZoomTest` | 缓动曲线、dt 上限、帧率无关、范围夹取 |
| `zoom/ZoomDispatcherTest` | 合并为最新值、看门狗、超时后继续消费、close 后拒收 |
| `focus/FocusAfTrackerTest` | 陈旧锁焦拒绝、扫描态校验、reset 保留基线 |
| `storage/PendingPhotoJournalTest` | 转义往返、阶段推进、损坏日志丢弃 |
| `photo/PhotoTargetSizeTest` | 宽高比匹配、硬件上限等比夹取、偶数尺寸 |
| `photo/JpegPolicyTest` | 画质优先级 100 / ZSL 95 / 默认 98、覆盖优先 |
| `photo/PendingCaptureBudgetTest` | 张数与字节双闸门、释放不为负 |
| `video/VideoStabilizationPolicyTest` | 包络降级到 FHD/30、REQUIRE 不可满足 |
| `util/MediaNamingTest` | 命名格式、字典序＝时间序、同毫秒去重 |
| `internal/CaptureProfileFactoryTest` | ZSL/闪光/扩展/画质组合、帧率挑选 |

未覆盖（需真机或 instrumentation）：实际绑定与降级、Camera2 回调、MediaStore 落库、中断/杀进程恢复。

## 12. 后续可扩展点

- 曝光补偿（`CameraControl.setExposureCompensationIndex`）与测光区域。
- 变焦的 `setLinearZoom` 曲线（当前用倍率线性缓动，广角端手感偏快）。
- 录像期间切镜头（CameraX 1.4+ 支持 `CameraEffect`/`CameraInfo` 组合，但需要重绑与 `Recording` 迁移）。
- 多帧合成/夜景的软件管线（当前只透传厂商扩展）。
