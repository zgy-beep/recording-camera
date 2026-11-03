# 提取对照表（Alibi → camera-core）

源仓库：`T:\Github\Alibi`，包 `app.myzel394.alibi`。下表是每个原始文件里被抽出来的行为，以及它在新库中的落点。
凡是“行为保留、形态改变”的地方都写了原因；UI、DataStore 设置、Hilt 注入、Compose 一律不搬。

## 拍照

| 原文件 | 提取内容 | 新落点 |
| --- | --- | --- |
| `services/PhotoCaptureService.kt`（2401 行） | 会话状态机、绑定重试、拍照流水线、配额、日志恢复、保存 worker | `CameraCore.kt`（会话编排）、`photo/PhotoCaptureController.kt`（流水线）、`internal/CameraBinder.kt`（降级链） |
| 同上：`FastCaptureProfile` | ZSL/OIS/CAF/HQ NR/EDGE 的开启条件、帧率挑选 | `internal/CaptureProfile.kt` + `CaptureProfileFactory` |
| 同上：`buildFastCaptureProfile` | 从 Camera2 特征读能力 | `internal/CameraCapabilitiesReader.kt` |
| 同上：`resolveCameraExtension` | 夜拍优先于 HDR、扩展不支持时静默回退、扩展下禁用分析流 | `internal/ExtensionsResolver.kt` |
| `helpers/PhotoCaptureJpegEncoder.kt` | 画质 100 / ZSL 95 / 默认 98、软件 JPEG 开关 | `photo/JpegPolicy.kt`（改为纯函数，便于单测） |
| `helpers/PhotoCaptureTargets.kt` | 4:3 / 16:9 目标分辨率、按硬件 JPEG 上限夹取 | `photo/PhotoTargetSize.kt` |
| `helpers/PhotoNaming.kt` | `alibi-photo_yyyyMMdd_HHmmss_SSS_<8位>.jpg` | `util/MediaNaming.kt`（前缀改为 `camera`，随机后缀 base36） |
| `helpers/PhotoStorage.kt` | 内部目录 / MediaStore / SAF 三目标 | `storage/{PhotoSink,GalleryPhotoSink,DocumentTreePhotoSink}.kt` |
| `helpers/PhotoRepository.kt` | 索引 + 待处理记录 + 崩溃恢复 | `storage/PendingPhotoJournal.kt`（去掉 `photo_index.json`，只保留“未完成”日志——索引属于业务层，不属于相机核心） |
| `services/PhotoCaptureSession.kt` | 状态枚举、`MAX_PENDING_CAPTURES=8`、128 MiB 预算 | `photo/PendingCaptureBudget.kt` + `CameraCoreState.kt` |

## 变焦 / 对焦

| 原文件 | 提取内容 | 新落点 |
| --- | --- | --- |
| `services/SmoothZoom.kt` | 指数缓动、`snapTo`/`scaleBy`、范围夹取、dt 上限 | `zoom/SmoothZoom.kt`（逻辑不变，补了 `MAX_STEP_MILLIS` 常量与帧率无关性单测） |
| `services/ZoomRequestQueue.kt`（32 ms 时间戳合并） | “只发最新倍率”的意图 | `zoom/ZoomDispatcher.kt`：`Channel(CONFLATED)` + 在途等待 + 150 ms 看门狗，**结构上**保证 latest-wins，不再依赖时间戳比较 |
| `PhotoCaptureService` 内 16 ms ticker / `VideoRecorderService.CameraControl` 内 ticker | 逐帧推进 + 死区发布 | `zoom/ZoomController.kt`（拍照/录像共用一份） |
| `services/FocusAfState.kt` | 只接受“扫描过后”的终态 | `focus/FocusAfTracker.kt`（`reset()` 保留基线，见 ARCHITECTURE §7） |
| `PhotoCaptureService.focus()` / `VideoRecorderService.focusAt()` | 取消再对焦、3 s 自动取消、2.5 s 超时 | `focus/FocusController.kt`（两处重复实现合并为一处） |
| `services/ZoomCaptureDiagnostics.kt` | 用 Camera2 会话回调喂 AF_STATE | 同一个 `FocusController.captureCallback` |

## 录像

| 原文件 | 提取内容 | 新落点 |
| --- | --- | --- |
| `services/VideoRecorderService.kt`（992 行） | 录像状态机、输出分配、事件处理、收尾等待 | `video/VideoRecordingController.kt` |
| `services/VideoStabilizationPolicy.kt` | 防抖包络判断（UHD/60fps → FHD/30） | `video/VideoStabilizationPolicy.kt`（`Decision` 增加 `unsatisfied`，让 `REQUIRE` 能明确失败） |
| `VideoRecorderService` 输出构建（三个分支） | SAF / MediaStore / 私有目录 | `video/VideoOutputFactory.kt` 的 `AllocatedVideoOutput` 三实现，善后逻辑统一 |
| `VideoRecorderService.CameraControl` | 变焦/对焦/手电所有权 | 拆到 `zoom/ZoomController`、`focus/FocusController`、`CameraCore.setTorch` |

## 未搬的部分及原因

- `ui/screens/StandardCameraScreen.kt`、`ui/components/RecorderScreen/atoms/CameraPreview.kt`、`ui/models/PhotoCaptureModel.kt`：界面与状态投影，用户明确要求“暂时不要 UI”。
- DataStore 设置模型（画质策略、保存位置、命名模板）：换成 `Config.kt` 的不可变配置对象，宿主自行持久化。
- Hilt 模块、通知渠道、前台服务生命周期：属于宿主职责，用 `SimpleLifecycleOwner` 暴露接入点。
- `photo_index.json` 索引：相机核心不维护相册索引，避免和宿主业务表重复。

## 行为差异（有意为之）

1. 拍照与录像**同一个会话**：原实现切换模式整轮重绑，切换有黑屏与 1–2 s 延迟；现在一次性绑定三种用例，切换只是调 API。
2. 变焦合并机制换成结构化 conflated 通道（见上）。
3. 焦点状态改为 `StateFlow<FocusState>`（原为回调 + 多个布尔字段）。
4. 错误改为类型化 `CameraError`（原为 `String` 消息）。
5. 新增：`lastPhoto`/`lastVideo` 状态、`updateRotation()`、录制时长/体积上限自动停止、录像收尾看门狗、镜头切换保留倍率。
