# Disc-Jockey-dt

在 Minecraft 里播放音符盒歌曲（`.nbs` / MIDI）。这是 **Disc Jockey 的 Minecraft 26.2 移植与修复改版**。

> 模组 ID 仍是 `disc_jockey`，与上游一致（配置文件路径、整合包依赖都靠它）。
> 支持 Minecraft **26.2**（Fabric，Java 25），运行需要 **Fabric API** 与 **Cloth Config**（Mod Menu 可选，仅配置界面入口）。

---

## 来源与谱系

| 环节 | 来源 | 许可 |
| --- | --- | --- |
| 原作 Disc Jockey | [SemmieDev/Disc-Jockey](https://github.com/SemmieDev/Disc-Jockey)（止于 1.7.0 / MC 1.21） | MIT |
| 1.21.11 / 26.2 分支与新增功能 | [xjjakm/Disc-Jockey](https://github.com/xjjakm/Disc-Jockey)（`1.21.11`、`26.2` 分支） | MIT |
| 本改版（Disc-Jockey-dt） | 本仓库 | MIT（沿用上游） |

本仓库以 xjjakm 的 **26.2 分支**为基线，把我们在 **1.21.11 移植线**上做的全部改动搬了过来，并修掉两处会影响实际播放的问题。

## 相对上游 26.2 分支做了什么

### 一、从 1.21.11 移植线搬回的功能

* **MIDI 无条件支持** —— 删掉了 `enableExperimentalMIDI` 实验开关，`.mid` / `.midi` 与 `.nbs` 一样始终参与扫描和播放（可变速度 tempo map、音高窗口映射、打击乐表、GS/XG bank select）。
* **懒解析 + 快速重扫** —— MIDI 先只登记元数据（列表显示 `<文件名> [midi]`、时长 0:00），首次播放/试听时才真正解析（`SongLoader.ensureSongLoaded`）；扫描走有界线程池并行解析，并用 `路径 + mtime + size` 增量缓存复用未变化的歌曲，列表一次性原子发布。每轮扫描在日志里输出 `Song list loaded: N songs (X parsed, Y cached) in Z ms`。
* **服务器版本自动检测** —— `Config.expectedServerVersion` 不再由用户配置（界面上隐藏），改为每个客户端 tick 依据 `player.blockInteractionRange()` 自动判定（`> 5` → `v1_20_5_Or_Later`，否则通用档）。
* **播放速度 0.1x – 20x** —— 播放面板里的对数滑块，以及 `/discjockey speed` 命令（同一套钳制）。
* **⟳ 刷新播放列表按钮** —— 歌曲列表右下角，重扫完成后重建列表并恢复当前文件夹与选中项。
* **MIDI 音色对齐 Open Note Block Studio** —— 内置 ONBS 的 `midi_ins` / `midi_drum` 表（128 个 GM 音色 + 每音色八度补偿；64 个打击乐条目含各自音高），写入歌曲数据用 `Note.nbsId()` 而不是枚举 `ordinal()`。

### 二、本改版修复（26.2 线）

* **音色映射回归 ONBS**：26.2 上游基线的 `INSTRUMENT_MAP` 把 GM 铜管族 56–63 映射到 26.2 新增的 4 个铜管乐器（`TRUMPET*`），并把这 8 个音色的八度补偿改成 0。但 NBS 规范里乐器编号 **`>= 16` 属于"自定义乐器区"**，把这些编号写进歌曲数据既偏离 ONBS，也会和文件里的自定义乐器撞号。现在铜管族按 ONBS 原始表处理（回退 Flute / Didgeridoo，八度补偿取 ONBS 原值），`PROGRAM_ID` / `PROGRAM_OCTAVE` / `DRUM_ID` / `DRUM_PITCH` 四张表与 ONBS 逐项一致。
* **NBS 自定义乐器不再让歌曲崩溃**：文件里 `instrument >= vanillaInstrumentCount`（现代文件为 16）的音符表示自定义乐器，其音源在文件末尾的可选段里，模组无法播放。旧代码直接拿这个编号去索引 16/20 项的乐器表，会抛 `ArrayIndexOutOfBoundsException`（随后播放线程再抛 `Index 0 out of bounds for length 0`）。现在这类音符**回落为 Harp** 并照常播放，同时打一行日志说明有多少个音符被回落：
  `Song "<文件>": N of M notes use custom instruments (NBS instrument id >= 16), which this mod cannot play - using Harp instead`
* **空歌曲保护**：解析失败或真的没有音符的歌曲不会再启动播放/试听线程。
* **打包修复**：jar 里内嵌的许可证文件从 `LICENSE_null` 正名为 `LICENSE_disc_jockey`。

### 三、行为说明（与上游不同或需要注意的地方）

* 配置文件位置：`config/disc_jockey/config.json`（不是旧的 `disc_jockey.json5`），旧设置不会自动迁移。
* 自定义乐器音符以 Harp 发声（见上），这是本模组的能力边界 —— 它不会去加载自定义音源文件。
* 26.2 的 4 个铜管乐器仍保留在乐器表里（编号 16–19）：只有 `.nbs` 自己声明 `vanillaInstrumentCount = 20` 时才会用到；MIDI 转歌曲时不会写出这几个编号。
* 增量缓存只比对 `mtime + size`：两者都不变而内容变了的情况检测不到。
* 扩展名不是 `.nbs` / `.mid` / `.midi` 的文件会被跳过（无扩展名的文件仍按 NBS 尝试）。
* `gui/hud/BlocksOverlay`（音符盒计数 HUD）沿用上游 26.2 的做法，未注册到 HUD —— 所需音符盒清单由界面上的 "Blocks" 按钮输出到聊天栏。

## 构建

需要 **JDK 25**（Minecraft 26.2 是 Java 25）：

```bash
./gradlew build          # -> build/libs/disc_jockey-1.9.8+mc26.2.jar
```

* 工具链：Gradle `9.6.0`（`gradle/wrapper/gradle-wrapper.properties` 指向腾讯镜像，因为本机 `services.gradle.org` 不可达）、Fabric Loom `1.17-SNAPSHOT`，**不需要 mappings**（26.2 起 Minecraft 不再混淆）。
* 依赖：Fabric Loader `0.19.3`、Fabric API `0.161.0+26.2`、Cloth Config `26.2.155`、Mod Menu `20.0.1`（经由 Modrinth maven）。
* 版本号在 `gradle.properties` 的 `mod_version`。

## 安装

把构建出的 `disc_jockey-*.jar` 放进 `.minecraft/mods/`，并确保已安装：

* Fabric Loader ≥ 0.19.3
* [Fabric API](https://modrinth.com/mod/fabric-api)
* [Cloth Config](https://modrinth.com/mod/cloth-config)（配置界面必需）
* [Mod Menu](https://modrinth.com/mod/modmenu)（可选，仅提供配置入口）

## 用法速览

* 把 `.nbs` / `.mid` 歌曲放进 `config/disc_jockey/songs/`（支持子文件夹，会递归扫描）。
* 默认按键打开歌曲列表（可在配置里改）；选中歌曲后点播放，角色会在音符盒附近按谱演奏。
* 播放面板提供播放模式（单曲循环 / 列表循环 / 随机 / 播完停止）、速度滑块、试听进度；列表右下角 ⟳ 用于重扫曲库。
* `/discjockey` 提供命令入口（如 `/discjockey speed <0.1-20>`、播放/停止等），可用 TAB 补全歌曲名。

## 许可与致谢

* 本仓库代码沿用上游 **MIT** 许可，见 [LICENSE](LICENSE)。按 MIT 要求，请保留原版权声明：
  `Copyright (c) 2022 Semmieboy_YT`。
* 作者署名（与 `fabric.mod.json` 一致）：
  * **SemmieDev** —— 原作者（Disc Jockey，上游止于 1.7.0 / MC 1.21）
  * **xjjakm** —— 二次修改作者（[xjjakm/Disc-Jockey](https://github.com/xjjakm/Disc-Jockey) 的 `26.2` / `1.21.11` 分支，本改版的基线）
  * **sd_dt**、**deepseekfl4.1** —— 本改版（26.2 移植整合 + 音色映射与自定义乐器修复）
* 上游贡献者：EnderKill98、myueqf、EnderPhantomWing、chxjj。
* MIDI 音色映射表取自 [Open Note Block Studio](https://github.com/OpenNBS/NoteBlockStudio) 的 `midi_instruments` 脚本。
* 歌曲文件（`.nbs` / `.mid`）的版权属于各自的曲作者，与本仓库无关。
