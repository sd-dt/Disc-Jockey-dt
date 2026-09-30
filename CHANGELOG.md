# 更新日志

本仓库是 Disc Jockey 的 **Minecraft 26.2 移植与修复改版**（Disc-Jockey-dt）。上游信息见 README。

## disc_jockey-1.9.8+mc26.2

### 2026-10-01 · 首次发布（dt）

* **音色映射回归 Open Note Block Studio**：GM 铜管族 56–63 不再被写成 26.2 铜管乐器在 NBS 里的扩展编号（16–19，属"自定义乐器区"），改回 ONBS 原始映射（Flute / Didgeridoo）与 ONBS 的八度补偿；四张映射表与 ONBS 逐项一致。
* **NBS 自定义乐器不再导致歌曲加载/播放崩溃**：`instrument >= vanillaInstrumentCount`（现代文件为 16）的音符回落为 Harp 并照常播放，同时输出统计日志；播放/试听侧一律走边界安全的取音色方法。
* **空歌曲保护**：没有可播放音符的歌曲不再启动播放/试听线程（旧版会在播放线程抛 `Index 0 out of bounds for length 0`）。
* **打包修复**：jar 内嵌许可证文件由 `LICENSE_null` 正名为 `LICENSE_disc_jockey`。
* **元数据**：模组名 `Disc-Jockey-dt`，描述与 `contact` 指向本仓库，作者标注原作者的移植链（SemmieDev / xjjakm / sd_dt）。

### 2026-09-24 · 26.2 移植

以 [xjjakm/Disc-Jockey](https://github.com/xjjakm/Disc-Jockey) 的 `26.2` 分支为基线，把 1.21.11 移植线上的全部改动搬入：

* MIDI 无条件支持（删除 `enableExperimentalMIDI` 开关）
* MIDI 懒解析 + 有界线程池并行扫描 + `mtime + size` 增量缓存 + 列表原子发布
* `expectedServerVersion` 按 `player.blockInteractionRange()` 每 tick 自动检测
* 播放速度 0.1x–20x（滑块 + `/discjockey speed`）
* 歌曲列表右下角 ⟳ 刷新播放列表按钮
* MIDI 音色映射改用 ONBS 表 + `Note.nbsId()`（修掉枚举 `ordinal()` 造成的整体音色错位）
