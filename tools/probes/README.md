# tools/probes

开发期用的**一次性小程序**：直接 `main()` 跑，**不参与 Gradle 构建**，用来量匹配算法的各种指标。
它们当初都是拿 `kotlinc` 单独编译的（见 docs/匹配算法.md 第七节）。

| 文件 | 用途 |
| --- | --- |
| `Harness*.kt` | 全量题库 × 多种输入长度的批量评测（最早的几代，保留作对照） |
| `MatchProbe.kt`、`CoverageProbe.kt` | 单对（库条目 × 输入）打印四条路线各多少分，查「库里有却说没有」最快 |
| `DfProbe.kt`、`DfDistProbe.kt` | 一个库的字组 DF 分布 —— 判断「通用问法」判据在这个库上还成不成立 |
| `XlsProbe.kt` | 用应用自己的解析链路读一个 .xls，看拆成几道题、字段对不对 |
| `XlsMatchProbe.kt` | 某道题在某个库里到底是几分、被哪条判据挡掉的 |
| `XlsSelfMatchProbe.kt` | 整库自匹配：每条题拿自己去搜全库，统计命中 / 错答 / 无候选（`--noise` 掺错字，`--no-options` 模拟旧行为） |
| `CropProbe.kt`、`CropRealProbe.kt` | 框选建议在真实屏幕尺寸/真实图片上的表现 |
| `PhotoPathProbe.kt` | 拍题整条链路（OCR → 清洗 → 匹配）在真实截图上的端到端复现 |
| `RepairProbe.kt`、`RecollectProbe.kt` | 文本修复与重收流程的定点复现 |
| `AndroidIcuStub.kt`、`OcrLineStub.kt`、`QuestionTypeStub.kt` | 脱离 Android 编译时用的替身（ICU 的繁体转换、OCR 行结构、题型常量） |

**这些程序需要你自己的题库文件**（`*.xls` / 题库导出的 txt），仓库里不包含任何题库数据。
