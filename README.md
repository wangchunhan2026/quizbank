# 题库助手（QuizBank）

> 一个 Android 端的「拍题 + 题库管理」工具：把任意 Excel / CSV / JSON 题库导入成自己的库，
> 覆盖在刷题 App 上一键拍题，本地匹配出答案；匹配不到时再调大模型。
>
> An Android app that turns *your own* question bank (Excel/CSV/JSON) into a fast,
> fully offline-capable "snap a photo → get the answer" tool. On-device OCR + local
> similarity matching first, LLM only as fallback.

**为什么会有这个项目**：市面上的搜题 App 搜的是它们的题库，不是你的。考研、执业医师、行业内部考试
（铁路、电力、民航、护理……）的题往往只以 Excel 或纸质资料存在，网上搜不到答案。这个工具解决的就是
「我手上有一份题库，怎么让它在手机上秒出答案」。

---

## 功能

| 模块 | 说明 |
| --- | --- |
| **拍题** | 悬浮窗覆盖在任意 App 上（刷题软件、PDF、拍照），框选一块区域 → 本地 OCR → 匹配 → 结果卡片显示答案与解析 |
| **搜题** | 直接在应用内输入/粘贴题干，走同一套匹配 |
| **收录** | 把屏幕上的题收进自己的库：OCR 出来可以逐行校对、选项自动分栏，重复题会先问你怎么处理 |
| **题库管理** | 多题库、批量导入（.xls / .xlsx / .csv / .txt / .json）、导出 CSV、跨库查重、相似题成组清理 |
| **导入去重** | 逐条给出「保留哪一条」的选择；支持近似题（OCR 差一个字）、跨库重复、放弃导入不留下空库 |
| **AI 兜底** | 本地匹配不到时才问大模型；支持 DeepSeek 官方与**任意 OpenAI 兼容接口**（内置 8 家预设，带连接测试与模型下拉） |
| **识别路线** | 纯 OCR / 视觉模型 / 自动切换；纯文字通道不会把截图发给只吃文字的服务商 |

## 快速开始

环境：JDK 17、Android SDK Platform 35（Build-Tools 35）。

```bash
git clone https://github.com/wangchunhan2026/quizbank.git
cd quizbank

# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 编译调试包（包名 com.daily.quizbank.debug，可与正式包共存）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. 单元测试（纯逻辑，不需要模拟器）
./gradlew test
```

> **路径必须全英文**。工程路径或 Android SDK 路径里带中文时，`gradlew test` 会在测试 JVM 启动阶段直接崩掉
> （现象：`Gradle Test Executor 1 finished with non-zero exit value 1`，且一条用例都没跑），
> 编译不受影响。这是测试执行器读 classpath 的编码问题，不是代码问题 —— 换到纯英文路径即可。

**正式包**需要自己的签名文件，仓库里不带。把 `keystore.properties`（`storeFile / storePassword / keyAlias / keyPassword`）
和你自己的 .jks 放在工程根目录即可；没有这两样时构建会自动跳过签名，产出未签名包。

首次使用：建题库 → 导入题库文件 → 打开悬浮球 → 在刷题 App 里拍一道题。

## 架构

```
app/src/main/java/com/daily/quizbank/
├─ data/
│  ├─ db/          Room：题库、题目、答题记录、用量
│  ├─ import/      题库解析：Cfb + Biff 手写 BIFF8（.xls）、xlsx、csv、txt、json
│  ├─ repo/        QuestionRepository：导入、去重、匹配、导出、相似题
│  ├─ ai/          DeepSeekClient（OpenAI 协议）、ModelProviders（服务商预设）
│  └─ prefs/       DataStore：设置、提示词、识别路线
├─ util/
│  ├─ Similarity.kt   四条相似度路线（见下）
│  ├─ StemMatcher.kt  题库索引 + 「通用问法」防误判
│  ├─ DedupPolicy.kt  纯逻辑的重复判定策略（可单测）
│  ├─ CropSuggest.kt  拍题框选建议（题干/选项/整块）
│  └─ OcrText.kt      OCR 文本清洗、选项识别、繁简
├─ service/        FloatingWindowService（悬浮球/面板）、ResultPanel、CollectBus
└─ ui/             Compose 界面：题库列表、详情、导入、设置、收集
```

## 匹配算法

这是整个项目最花心思的地方。摘要如下，细节见 [docs/matching-algorithm.md](docs/matching-algorithm.md)。

四条路线同时算，取最高分：

1. **整串 Dice** —— 两边完整的字符二元组重合度；
2. **滑动窗口 Dice** —— 拿输入切窗去套题库条目，解决「截图只框到一段」；
3. **覆盖率** —— 库里这条有多少比例出现在输入里；
4. **输入覆盖率** —— 输入有多少比例被这条盖住。

阈值：命中 ≥ 0.80，且与第二名差 ≥ 0.03 才采信（否则交给人或 AI）；0.42 是「摆出来但不采信」的展示线。

**最容易踩的坑**：库里那些只有一句小问的残缺条目（如「2021U2-106 可能的诊断是」），
用一句通用问法就能整条盖住。所以有一条判据：短条目被长输入整条覆盖、且覆盖它的字组**在库里都不稀奇**
（DF ≥ 2）时，判为「只是撞上一句通用问法」而丢弃。

这条判据后来在**主题高度集中**的题库上翻过车：业务库通篇讲降雪/会商时，一条完整题目的每个字组
DF 都 ≥ 2，于是它 100% 对上了却被丢掉（那个库 62% 的题都这样）。修法是加一层「同一道题」的证据 ——
**输入里出现了这条题自己的选项**（选项只给短条目入索引，且至少 8 个字组、命中过半）就放行。
判据只放宽、不新增候选，所以不会把该退回 AI 的题变成误答。

## 导入去重

`DedupPolicy.kt` 把它做成了纯函数，方便单测：

- **完全重复**：归一化后题干相同 → 直接问用户；
- **近似重复**：相似度 ≥ 0.88，或小问覆盖率 ≥ 0.90，或题干尾部一致 → 列为「疑似」；
- **跨库重复**：同一道题散落在多个题库里也提示；
- 用户的选择：保留新导入的 / 保留原有的 / 两条都留，逐条决定；
- 放弃整次导入时，回滚掉已经插入的行，不留空库。

## 测试与回归

- `app/src/test` 下 170 个 JVM 单元测试（相似度、匹配、去重、解析器、OCR 清洗、框选建议）；
- 匹配算法改动必须跑两套回归：**全量题库 × 13 种输入长度**（整题 / 前 3/4 / 前 1/2 / 前 20~4 字 / 中间 8~10 字），
  要求 **误答恒为 0**；以及**真机题库自匹配**。
- 这两套的题库是私有的，不随仓库发布；`tools/probes/` 里是当时用的探针程序，换成你自己的题库就能跑。

## 数据与隐私

- 题库、记录、设置全部存在设备本地（Room + DataStore），没有账号、没有服务端；
- API Key 只写进本机 DataStore，代码里没有任何硬编码密钥；
- 只有当你主动点「问 AI」时，题干（必要时加截图）才会发给你自己配置的模型接口；
- 应用不含任何题库内容，**不附带、也不分发第三方题库资料**。

## 已知限制

- 只支持 Android（8.0+，minSdk 26）；
- 匹配是字符级相似度，不理解语义 —— 问法被改写的题会退回 AI；
- .xls 解析是自己写的 BIFF8 子集，只覆盖题库导出常见的单元格类型（数字/文本/公式缓存值）；
- 悬浮窗需要「显示在其他应用上层」权限，部分厂商 ROM 还要额外允许后台运行。

## 关于收费

**免费，没有内购、没有激活码、没有功能限制。** 最早写过一版一机一码、准备卖出去，后来决定直接开源，
所以代码里没有任何授权校验 —— 编译出自己的包，导入自己的题库就能用。如果哪天在别处看到有人卖这个，
那不是我，你也不用买。

## 许可

MIT，见 [LICENSE](LICENSE)。题库内容的版权归各自权利人，请只导入你有权使用的资料。
