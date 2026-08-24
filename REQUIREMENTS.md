# MeetingApp 需求与实现文档

> 一个 Android 会议助手 App：录音、实时转写、说话人识别、AI 唤醒词问答、自动生成会议纪要。
> 本文档记录**产品需求**以及**当前的实现方式**，供后续开发参考。

---

## 1. 产品需求

### 1.1 已实现的需求

| 编号 | 需求 | 状态 |
|------|------|------|
| R1 | 会议全程录音，采集麦克风音频 | ✅ 已实现 |
| R2 | 把会议中每个人的发言实时转成文字（分段转写） | ✅ 已实现 |
| R3 | 区分说话人（Speaker 标签），支持手动指派人名 | ⚠️ 部分实现（见下） |
| R4 | AI 唤醒词（默认「小谈」）：会中喊出唤醒词即可向 AI 提问，AI 语音回答 | ✅ 已实现 |
| R5 | 会议结束时**自动**生成结构化会议纪要（议题、各方观点、共识、行动项） | ✅ 已实现 |
| R6 | 纪要可编辑、保存、导出（邮件 / Google Drive） | ✅ 已实现 |

### 1.2 待实现 / 计划中的需求

| 编号 | 需求 | 状态 | 说明 |
|------|------|------|------|
| R7 | **会议结束时保存一份完整的会议语音文件** | ✅ 已实现 | 见 §3。M4A/AAC 完整录音，路径写入 `Meeting.audioFilePath`。 |
| R8 | 真正的说话人分离（diarization） | ❌ 未实现 | 当前 Whisper (whisper-1) 不做分离，每个片段都标为 "Speaker 1"，需人工指派。 |
| R9 | 音频文件的保留期清理（30 天） | ❌ 死代码 | `AudioChunkDao.deleteOlderThan` 无人调用，且只删数据库行、不删磁盘文件。 |
| R10 | **会议进行中的增量滚动纪要 + AI 唤醒时刷新上下文** | 📐 已设计（见 §5） | 会中周期性更新纪要草稿；AI 被唤醒时先刷新纪要再答。 |

---

## 2. 现有实现（录音 → 转写 → 纪要）

### 2.1 录音管线

- **采集**：`AudioRecord`（原始 PCM），音源 `MediaRecorder.AudioSource.MIC`。
  - 参数：16000 Hz、单声道、PCM 16-bit（[Constants.kt:6-9](app/src/main/java/com/meetingapp/util/Constants.kt#L6-L9)）。
- **入口服务**：`RecordingService`（前台绑定型 Service）——[RecordingService.kt](app/src/main/java/com/meetingapp/service/RecordingService.kt)。
  - 由 `ActiveMeetingViewModel.startMeeting()` 启动并绑定；`onServiceConnected` 里调用 `startRecording()`（[ActiveMeetingViewModel.kt:82-131](app/src/main/java/com/meetingapp/viewmodel/ActiveMeetingViewModel.kt#L82-L131)）。
  - 后台协程 read loop 循环读取 PCM，转发给 `AudioChunkWriter`（[RecordingService.kt:114-137](app/src/main/java/com/meetingapp/service/RecordingService.kt#L114-L137)）。
  - 持有 `PARTIAL_WAKE_LOCK` 防止息屏后被系统杀掉。

### 2.2 分块与转写

- PCM 先在内存缓冲（`AudioChunkWriter`，[AudioChunkWriter.kt](app/src/main/java/com/meetingapp/service/AudioChunkWriter.kt)）。
- 每累积到 **8 秒**（`CHUNK_DURATION_MS = 8_000L`）就 flush 一个片段：
  - **静音片段直接丢弃**（RMS < 150），避免 Whisper 对噪声/静音产生幻觉。
  - 非静音片段写成独立 WAV 文件 `chunk_<时间戳>.wav`，存于 `filesDir/audio/<meetingId>/`。
- 每个片段经有界 channel → `ChunkCallback.onChunkReady` 送去转写。
- 额外 flush 时机：AI 唤醒词触发时、会议结束时。

### 2.3 转写引擎

- **OpenAI Whisper**（模型 `whisper-1`），接口 `TranscribeApi` / 实现 `OpenAiTranscribeApi`。
- 上传片段 WAV（multipart），`response_format=verbose_json`，带语言与关键词提示（会议议题 + 参会人名）。
- **无说话人分离**：每个片段统一标 "Speaker 1"，合并为一段。
- 结果存 Room `segments` 表（`Segment` 实体），过滤 Whisper 幻觉短语。说话人名可后续按标签重新指派。

### 2.4 会议纪要

- 生成器 `OpenAiMinutesGenerator`（模型 `gpt-4o`），中文系统提示，产出结构化 Markdown 纪要。
- **自动触发**：会议结束 → 导航到回顾页 → `MinutesReviewViewModel.load()` 自动调用 `generateMinutes()`。
- 纪要基于**转写文字**（segments），不使用音频。存 `minutes` 表，可编辑/保存/邮件/Drive。

### 2.5 数据存储

- Room 数据库 `MeetingDatabase`（[MeetingDatabase.kt](app/src/main/java/com/meetingapp/data/db/MeetingDatabase.kt)），实体：
  `Meeting`、`Participant`、`MeetingParticipant`、`Segment`、`Minutes`、`AudioChunk`。
- 音频片段元数据存 `audio_chunks` 表（每片一行：`filePath`、`startMs`、`endMs`、`transcribed`）。

---

## 3. R7：完整会议语音文件（实现方案）

### 3.1 背景与问题

之前**没有**完整会议音频文件。整场会议的声音只存在为一堆分散的 8 秒 `chunk_*.wav`，且**静音段被丢弃**，时间线有缺口，无法还原完整录音。片段转写后也不会删除，长期堆积。

### 3.2 目标

会议全程边录边写**一份完整、连续的音频文件**（含静音），会议结束时可用。

### 3.3 设计决策

- **格式**：M4A / AAC（压缩，体积小；16kHz 单声道约每小时 10–15MB）。
- **编码**：`MediaCodec`（AAC 编码器）+ `MediaMuxer`（MP4/M4A 容器），新增类 `FullAudioRecorder`。
- **数据流**：`RecordingService` 的 read loop 把每块 PCM **同时**喂给：
  1. `AudioChunkWriter`（转写用，逻辑不变，仍丢弃静音）；
  2. `FullAudioRecorder`（完整录音，**不丢弃静音**）。
  两条链路并行、互不影响。
- **存储路径**：`filesDir/audio/<meetingId>/meeting_<meetingId>.m4a`。
- **数据库**：`Meeting` 实体新增 `audioFilePath: String?` 字段，会议结束时写入。Room 版本升级 + migration。

### 3.4 涉及文件

- 新增：`app/src/main/java/com/meetingapp/service/FullAudioRecorder.kt`
- 修改：`RecordingService.kt`、`ActiveMeetingViewModel.kt`、`Meeting.kt`、`MeetingDao.kt`、`MeetingDatabase.kt`（migration）

---

## 4. 技术栈速览

- Kotlin + Jetpack Compose + Hilt(DI) + Room(DB) + Coroutines
- 音频：`AudioRecord` 采集，`MediaCodec`/`MediaMuxer` 编码（R7）
- AI：OpenAI Whisper（转写）、GPT-4o（纪要/问答）、TTS-1（语音回答）

---

## 5. R10：会中增量滚动纪要 + AI 唤醒时刷新上下文（设计）

### 5.1 目标与原则

- 会议进行中就持续维护一份**纪要草稿**，让参会者实时看到成形的纪要。
- **不重新转写音频**：纪要基于已有转写文字（`segments`），完整录音文件只用于存档/回放。
- **增量修订而非全量重写**：每次把「已有纪要 + 新增转写」交给 AI，让它在旧稿上追加/修订，成本低、内容稳定不抖动。
- AI 被唤醒参与讨论时，先刷新纪要，让 AI 站在**整理后的完整上下文**上回答。

### 5.2 纪要刷新的三个触发点

| 触发 | 时机 | 输入给 AI | 输出 |
|------|------|-----------|------|
| **T1 常规滚动** | 累计**未纳入纪要的新增转写**超过阈值（约 400 字 ≈ 2 分钟），且距上次刷新 ≥ 最小间隔（45 秒） | 当前纪要草稿 + 仅新增的那段转写 | 修订后的纪要草稿 |
| **T2 AI 唤醒** | 用户喊唤醒词触发 `askAi` 时 | 先执行一次 T1 刷新；随后 AI 回答的上下文 = **最新纪要草稿 + 最近若干句原话** | 刷新后的草稿 + AI 回答 |
| **T3 结束定稿** | 会议结束、进入回顾页 | **全部转写全文**（兜底，保证最终质量） | 最终纪要（现有逻辑） |

> T3 用全文重生成，是为了弥补增量修订可能丢失早期细节的弱点。

### 5.3 AI 唤醒时的上下文（T2 细节）

当前 `askAi` 已会先 `flushCurrentChunk()` 转写最新音频。R10 在此基础上：
1. flush 最新音频 → 转写落库；
2. 触发一次纪要刷新（T1），得到覆盖前面全部内容的最新纪要草稿；
3. AI 回答的上下文 = **最新纪要草稿（浓缩的全局上下文）+ 最近 N 句原话（细节）**。

这取代当前 `buildContext()` 仅取最近 60 条 segment 的做法（[ActiveMeetingViewModel.kt:218-222](app/src/main/java/com/meetingapp/viewmodel/ActiveMeetingViewModel.kt#L218-L222)）。

### 5.4 数据与存储

- 复用 `minutes` 表存草稿。会中每次刷新**更新同一行**（`updateContent`），而非不断插入新行，避免堆积。
- 需区分「草稿」与「定稿」：可给 `Minutes` 加 `isDraft` 标记，或约定会中只保留一条最新草稿、结束时再写定稿。
- 需要一个「已纳入纪要的最后 segment 位置」游标，用于计算 T1 的「新增转写」。

### 5.5 待定 / 风险

- **UI**：会中纪要草稿显示在哪、如何与实时字幕共存 —— 需先做 UI 设计再实现。
- **并发**：T2（AI 唤醒）与 T1（滚动）可能同时触发刷新，需加锁或串行化，避免两次修订互相覆盖。
- **成本**：即便增量，AI 唤醒频繁也会累积调用；可对 T2 加最小间隔（如刚刷新过就跳过）。

### 5.6 实现选项（尚未开工）

- **A** 完整实现（含会中纪要预览 UI）
- **B** 仅后台逻辑（增量生成 + 存草稿 + AI 唤醒刷新），UI 预览后续再加 ← 建议先做
- **C** 仅本设计文档，UI 想清楚后再开工
