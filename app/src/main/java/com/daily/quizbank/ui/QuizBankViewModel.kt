package com.daily.quizbank.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.data.ai.ApiException
import com.daily.quizbank.data.ai.ChatMessage
import com.daily.quizbank.data.ai.Messages
import com.daily.quizbank.data.ai.ModelInfo
import com.daily.quizbank.data.import.ColumnMapping
import com.daily.quizbank.data.import.ImportSummary
import com.daily.quizbank.data.import.ImportedSheet
import com.daily.quizbank.data.repo.DuplicateDraft
import com.daily.quizbank.data.import.ParseResult
import com.daily.quizbank.data.import.QuestionDraft
import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.prefs.AppSettings
import com.daily.quizbank.data.prefs.Prompts
import com.daily.quizbank.util.DedupPolicy
import com.daily.quizbank.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Windows 文件名里不能出现的字符。题库名是用户随便起的，得洗一遍。 */
private val INVALID_FILE_CHARS = Regex("""[\\\\/:*?<>|]""")

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Loading(val fileName: String) : ImportUiState

    data class Ready(
        val fileName: String,
        val sheets: List<ImportedSheet>,
        /** -1 表示「合并全部工作表」。 */
        val sheetIndex: Int,
        val drafts: List<QuestionDraft>,
        val mapping: ColumnMapping,
        val warnings: List<String>,
        val selected: Set<Int>,
    ) : ImportUiState

    data class Failed(val message: String) : ImportUiState
    data class Done(val summary: ImportSummary, val bankName: String, val bankId: Long) : ImportUiState

    /**
     * 检测到重复题，等用户拿主意。
     *
     * 之所以要停一下而不是默默跳过：真实题库里存在「同一道题出现两次、选项一字不差、
     * 答案一个标 C 一个标 B」的情况，光说「有 500 道重复」用户没法判断该留哪个。
     *
     * [bankId] 可能是 -1：目标题库要等**真正入库那一刻**才建（见 [commitImport]），
     * 这样用户看完重复题改变主意不导了，库里也不会留下一个空题库。
     */
    data class Duplicates(
        val bankId: Long,
        val bankName: String,
        val subject: String,
        val drafts: List<QuestionDraft>,
        val duplicates: List<DuplicateDraft>,
    ) : ImportUiState {
        /** 只是很像、不保证是同一道题的那几条（判据见 DedupPolicy）。 */
        val nearCount: Int get() = duplicates.count { it.near }

        /** 对照项在**别的**题库里的那几条 —— 这次导入动不了别的库，只能选「这条不入库」。 */
        val otherBankCount: Int get() = duplicates.count { it.otherBank }
    }

    /** 用户选了「逐一核对」，正在一道一道确认。 */
    data class Reviewing(
        val bankId: Long,
        val bankName: String,
        val subject: String,
        val drafts: List<QuestionDraft>,
        val duplicates: List<DuplicateDraft>,
        val index: Int,
        /** 已决定丢弃的 drafts 下标。 */
        val dropped: Set<Int>,
        /** 选了「留新的」，要**从库里顶掉**的旧题 id。不顶掉他这一选等于没选。 */
        val replaced: Set<Long>,
        /** 选了「留原来那条」时对照过的旧题 id —— 这些不能被别的选择顺手删掉。 */
        val keptOld: Set<Long>,
        /** 选了「两条都留」的条数（近似的那几条默认就是这个）。 */
        val keptBoth: Int,
        /**
         * 用户点选「这两份里留哪一份」；null = 还没选。
         *
         * 之所以要显式点选：之前是「保留这一条 / 删掉这一条」两个按钮，用户根本不知道
         * 「这一条」指哪一条 —— 是把两道都导入？还是把这道题整个舍弃？两边摆在一起时，
         * 真正要回答的问题只有一个：**这两份里留哪一份**。
         */
        val keep: DedupPolicy.Keep?,
    ) : ImportUiState {
        val current: DuplicateDraft get() = duplicates[index]
        val total: Int get() = duplicates.size
    }
}

/** 遇到重复题时的三种处理方式。 */
enum class DedupChoice {
    /** 全部导入：重复题也入库。 */
    IMPORT_ALL,

    /** 重复的直接舍去，不看题目（和旧版本一样）。 */
    SKIP,

    /** 一道一道核对。 */
    REVIEW,
}

data class ChatEntry(
    val role: String,
    val text: String,
    val thinking: String = "",
    val streaming: Boolean = false,
)

class QuizBankViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as QuizBankApp
    val repository = app.repository
    val settingsStore = app.settings

    // ------------------------------------------------------------------ 导入

    var importState by mutableStateOf<ImportUiState>(ImportUiState.Idle)
        private set

    private var loadedFile: com.daily.quizbank.data.import.ImportService.LoadedFile? = null
    private var parsedSheets: List<ParseResult> = emptyList()

    /** 从题库详情页点「导入」时预选的目标题库。 */
    var preferredBankId by mutableStateOf<Long?>(null)

    fun startImport(uri: Uri) {
        importState = ImportUiState.Loading("正在读取文件…")
        viewModelScope.launch {
            runCatching { app.importService.load(uri) }
                .onSuccess { file ->
                    loadedFile = file
                    if (file.sheets.isEmpty()) {
                        importState = ImportUiState.Failed("这个文件里没有可读的表格")
                        return@onSuccess
                    }
                    // 一次性把所有工作表都解析好，这样「全部」选项才能用 ——
                    // 否则用户有单选、判断两张表就得分开导两次。
                    parsedSheets = file.sheets.map { app.importService.parseSheet(it) }
                    importState = buildReady(file.displayName, ACTIVE_ALL_SHEETS)
                }
                .onFailure { error ->
                    importState = ImportUiState.Failed(error.message ?: "读取失败")
                }
        }
    }

    /** [index] 传 [ACTIVE_ALL_SHEETS] 表示合并全部工作表。 */
    fun selectSheet(index: Int) {
        val file = loadedFile ?: return
        if (index >= 0 && file.sheets.getOrNull(index) == null) return
        importState = buildReady(file.displayName, index)
    }

    private fun buildReady(fileName: String, activeIndex: Int): ImportUiState.Ready {
        val combined = activeIndex == ACTIVE_ALL_SHEETS
        val drafts = if (combined) {
            parsedSheets.flatMap { it.drafts }
        } else {
            parsedSheets.getOrNull(activeIndex)?.drafts.orEmpty()
        }
        val mapping = if (combined) {
            parsedSheets.firstOrNull()?.mapping ?: QuestionParser.emptyMapping()
        } else {
            parsedSheets.getOrNull(activeIndex)?.mapping ?: QuestionParser.emptyMapping()
        }
        val warnings = if (combined) {
            parsedSheets.flatMap { it.warnings }.distinct()
        } else {
            parsedSheets.getOrNull(activeIndex)?.warnings.orEmpty()
        }
        return ImportUiState.Ready(
            fileName = fileName,
            sheets = loadedFile?.sheets.orEmpty(),
            sheetIndex = activeIndex,
            drafts = drafts,
            mapping = mapping,
            warnings = warnings,
            selected = drafts.indices.filter { drafts[it].selected }.toSet(),
        )
    }

    fun toggleDraft(index: Int) {
        val state = importState as? ImportUiState.Ready ?: return
        val next = state.selected.toMutableSet()
        if (!next.add(index)) next.remove(index)
        importState = state.copy(selected = next)
    }

    fun selectAllDrafts(selected: Boolean) {
        val state = importState as? ImportUiState.Ready ?: return
        importState = state.copy(
            selected = if (selected) state.drafts.indices.toSet() else emptySet(),
        )
    }

    fun resetImport() {
        loadedFile = null
        importState = ImportUiState.Idle
    }

    /** 记住预览页的状态，供「取消重复处理」时回退。 */
    private var lastReady: ImportUiState.Ready? = null

    /**
     * 点「导入」：先只判重，**先不建库**。
     *
     * 建库推迟到 [finishImport] 有两个好处：用户看完重复题点「返回预览」、或者干脆退出时，
     * 库里不会多出一个空题库（2026-10-13 补的缺口）；判重也照样查得出来 —— 目标库还不存在时，
     * 库里所有题都算「别的题库里的」，跨库重复那一条正是靠这个才报得出来。
     */
    fun commitImport(bankName: String, subject: String) {
        val state = importState as? ImportUiState.Ready ?: return
        lastReady = state
        val drafts = state.selected.sorted().mapNotNull { state.drafts.getOrNull(it) }
        if (drafts.isEmpty()) return

        viewModelScope.launch {
            runCatching {
                // 同名题库直接复用；没有就是 -1，等入库那一刻再建
                val bankId = repository.bankIdByName(bankName) ?: -1L
                val duplicates = repository.findDuplicates(bankId, drafts)
                if (duplicates.isEmpty()) {
                    finishImport(
                        bankId = bankId,
                        bankName = bankName,
                        subject = subject,
                        drafts = drafts,
                        skipDuplicates = true,
                        resolved = 0,
                        replaceIds = emptyList(),
                        keptBoth = 0,
                    )
                } else {
                    importState = ImportUiState.Duplicates(bankId, bankName, subject, drafts, duplicates)
                }
            }.onFailure { error ->
                importState = ImportUiState.Failed(error.message ?: "入库失败")
            }
        }
    }

    private suspend fun finishImport(
        bankId: Long,
        bankName: String,
        subject: String,
        drafts: List<QuestionDraft>,
        skipDuplicates: Boolean,
        resolved: Int,
        replaceIds: List<Long>,
        keptBoth: Int,
    ) {
        // 到这一刻才真的建库（同名会复用）
        val targetId = if (bankId > 0L) bankId else repository.createBank(bankName, subject)
        val summary = repository.importDrafts(
            bankId = targetId,
            drafts = drafts,
            skipDuplicates = skipDuplicates,
            resolvedDuplicates = resolved,
            replaceIds = replaceIds,
            keptBoth = keptBoth,
        )
        importState = ImportUiState.Done(summary, bankName, targetId)
    }

    fun chooseDedup(choice: DedupChoice) {
        val state = importState as? ImportUiState.Duplicates ?: return
        when (choice) {
            DedupChoice.IMPORT_ALL -> runImport(state, choice)
            DedupChoice.SKIP -> runImport(state, choice)
            DedupChoice.REVIEW -> {
                val review = ImportUiState.Reviewing(
                    bankId = state.bankId,
                    bankName = state.bankName,
                    subject = state.subject,
                    drafts = state.drafts,
                    duplicates = state.duplicates,
                    index = 0,
                    dropped = emptySet(),
                    replaced = emptySet(),
                    keptOld = emptySet(),
                    keptBoth = 0,
                    keep = null,
                )
                importState = review.copy(keep = defaultKeep(review, review.current))
            }
        }
    }

    /** 「全部导入」= 一条都不动；「重复的直接舍去」= 一模一样的那些一条都不入库。 */
    private fun runImport(state: ImportUiState.Duplicates, choice: DedupChoice) {
        viewModelScope.launch {
            runCatching {
                val drafts = if (choice == DedupChoice.SKIP) {
                    // 入库那条路只认得「目标题库里 / 文件内部」的重复，**跨库**那几条它看不见，
                    // 所以在这一层先摘出去 —— 不然「舍去」说得出口、做不出来。
                    // 近似的那几条照常入库：它们未必是同一道题，不能拿一句「舍去」替他丢掉。
                    val skip = state.duplicates
                        .filter { it.exact && it.otherBank }
                        .map { it.index }
                        .toSet()
                    state.drafts.filterIndexed { index, _ -> index !in skip }
                } else {
                    state.drafts
                }
                finishImport(
                    bankId = state.bankId,
                    bankName = state.bankName,
                    subject = state.subject,
                    drafts = drafts,
                    skipDuplicates = choice == DedupChoice.SKIP,
                    resolved = 0,
                    replaceIds = emptyList(),
                    keptBoth = 0,
                )
            }.onFailure { error ->
                importState = ImportUiState.Failed(error.message ?: "入库失败")
            }
        }
    }

    /** 放弃重复处理，退回预览页。这一步还**没有建过库**，什么都不用清。 */
    fun cancelDedup() {
        lastReady?.let { importState = it }
    }

    /** 点选要保留的那一条。 */
    fun reviewSelect(keep: DedupPolicy.Keep) {
        val state = importState as? ImportUiState.Reviewing ?: return
        importState = state.copy(keep = keep)
    }

    /** 确定当前的选择，进入下一道。 */
    fun reviewConfirm() {
        val state = importState as? ImportUiState.Reviewing ?: return
        val keep = state.keep ?: return
        val next = withDecision(state, state.current, keep)
        if (state.index + 1 >= state.total) {
            commitReviewed(next)
        } else {
            val advanced = next.copy(index = state.index + 1, keep = null)
            importState = advanced.copy(keep = defaultKeep(advanced, advanced.current))
        }
    }

    /**
     * 剩下的全部保留新导入的 / 全部保留原来那条。500 道重复一道道点完是不现实的，得留出口。
     *
     * **近似的那几条不跟着按钮走**（[DedupPolicy.restKeep]）：它们未必是同一道题，
     * 一句话批量顶掉等于用户自己丢掉了半道题，而且他看不见丢了什么。
     */
    fun reviewRest(keepNew: Boolean) {
        val state = importState as? ImportUiState.Reviewing ?: return
        var acc = state
        state.duplicates.drop(state.index).forEach { dup ->
            acc = withDecision(acc, dup, DedupPolicy.restKeep(keepNew, dup.near))
        }
        commitReviewed(acc)
    }

    /**
     * 把「这一条选了哪个」累积进状态。
     *
     * 删谁、丢谁、什么情况下不许删，全在 [DedupPolicy.decide] 里，那边有单测 ——
     * 这段代码错一下就是用户库里少一道题，不能只靠眼睛看。
     */
    private fun withDecision(
        state: ImportUiState.Reviewing,
        dup: DuplicateDraft,
        keep: DedupPolicy.Keep,
    ): ImportUiState.Reviewing {
        val decision = DedupPolicy.decide(keep, dup.index, dup.earlierIndex, dup.counterpart)
        return state.copy(
            dropped = if (decision.dropDraftIndex >= 0) {
                state.dropped + decision.dropDraftIndex
            } else {
                state.dropped
            },
            // 选了「留新的」→ 库里那条要顶掉，否则这一选等于没选（拍题取的是先入库那条）
            replaced = if (decision.deleteQuestionId > 0L) {
                state.replaced + decision.deleteQuestionId
            } else {
                state.replaced
            },
            // 选「留原来那条」的对照项单独记：同一条旧题可能被文件里好几道题指着，
            // 一边选顶掉、一边选保留的时候不许删（见 DedupPolicy.replaceIds）
            keptOld = if (keep == DedupPolicy.Keep.OLD && dup.inBank) {
                state.keptOld + dup.existingId
            } else {
                state.keptOld
            },
            keptBoth = state.keptBoth + if (keep == DedupPolicy.Keep.BOTH) 1 else 0,
            keep = keep,
        )
    }

    /** 用户还没点之前默认选哪个（null = 必须他自己点），判据见 [DedupPolicy.defaultKeep]。 */
    private fun defaultKeep(
        state: ImportUiState.Reviewing,
        dup: DuplicateDraft,
    ): DedupPolicy.Keep? {
        val draft = state.drafts.getOrNull(dup.index)
        return DedupPolicy.defaultKeep(
            dup.near,
            dup.counterpart,
            DedupPolicy.Newcomer(
                answer = draft?.answer.orEmpty(),
                options = draft?.options.orEmpty(),
                analysis = draft?.analysis.orEmpty(),
                tags = draft?.tags.orEmpty(),
            ),
        )
    }

    private fun commitReviewed(state: ImportUiState.Reviewing) {
        val kept = state.drafts.filterIndexed { index, _ -> index !in state.dropped }
        // 顶掉谁由 DedupPolicy 说了算：同一条旧题既被选了顶掉、又被别的条目选了保留时不删
        val replaceIds = DedupPolicy.replaceIds(state.replaced, state.keptOld).toList()
        viewModelScope.launch {
            runCatching {
                finishImport(
                    bankId = state.bankId,
                    bankName = state.bankName,
                    subject = state.subject,
                    drafts = kept,
                    // 核对过的题是用户明确要留下的，这里不能再按「重复」把它们筛掉
                    skipDuplicates = false,
                    resolved = state.dropped.size,
                    replaceIds = replaceIds,
                    keptBoth = state.keptBoth,
                )
            }.onFailure { error ->
                importState = ImportUiState.Failed(error.message ?: "入库失败")
            }
        }
    }

    // ------------------------------------------------------------------ 设置

    var settingsMessage by mutableStateOf<String?>(null)
        private set

    var availableModels by mutableStateOf<List<ModelInfo>>(emptyList())
        private set

    var modelsLoading by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ 导出

    var exportMessage by mutableStateOf<String?>(null)
        private set

    fun clearExportMessage() {
        exportMessage = null
    }

    /**
     * 把题库导出成 CSV 并调起分享。
     *
     * 写到 filesDir/exports/ 而不是公共存储：Android 10 起分区存储之后，
     * 往下载目录写要么被拒、要么得申请权限；而 filesDir 正是 FileProvider 暴露出去的目录，
     * 在 res/xml/file_paths.xml 里已经声明好了（files-path name=exports）。
     *
     * 文件头要加 UTF-8 BOM：不加的话 Windows 版 Excel 打开会按 GBK 解，中文全变乱码 ——
     * 而这份文件的主要用途恰恰就是发给别人用 Excel 打开。
     */
    fun exportBank(bankId: Long, bankName: String) {
        viewModelScope.launch {
            exportMessage = null
            runCatching {
                val csv = app.repository.exportCsv(bankId)
                if (csv.lineSequence().count() <= 1) {
                    throw IllegalStateException("这个题库还是空的，没东西可导")
                }
                val safeName = bankName.replace(INVALID_FILE_CHARS, "_").ifBlank { "题库" }
                val dir = File(app.filesDir, "exports")
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    // 先清掉上一次导出的，免得攒一堆同名旧文件
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, safeName + ".csv").outputStream().use { out ->
                        out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        out.write(csv.toByteArray(Charsets.UTF_8))
                    }
                }
                File(dir, safeName + ".csv") to safeName
            }.onSuccess { exported ->
                val uri = FileProvider.getUriForFile(
                    app,
                    app.packageName + ".fileprovider",
                    exported.first,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, exported.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // 从 ViewModel 起来，手里没有 Activity 上下文，必须带 NEW_TASK
                app.startActivity(
                    Intent.createChooser(send, "导出题库").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                exportMessage = "已导出「" + exported.second + "」，选个应用发出去就行"
            }.onFailure { error ->
                exportMessage = error.message ?: "导出失败"
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.edit(transform) }
    }

    /**
     * 拉取模型列表。
     *
     * [apiKey] / [baseUrl] 必须由调用方把**输入框里此刻的值**传进来，不能用存盘的设置。
     *
     * 之前的写法是 `settingsStore.snapshot()`，而设置页的按钮顺序是「保存」旁边放着
     * 「拉取模型列表」，安装说明里写的流程也是「填 Key → 点拉取验证 → 保存」——
     * 于是每一次验证都拿着空 Key 去请求，必然回 Authentication Fails。
     * 这个 bug 是文档和代码一起把用户坑了，不是 Key 的问题。
     *
     * 顺带：visionModel 走 [AppSettings.DEFAULT_MODEL] 只是为了补全对象，
     * 列模型跟它无关。
     */
    fun fetchModels(apiKey: String, baseUrl: String) {
        modelsLoading = true
        settingsMessage = null
        viewModelScope.launch {
            val current = settingsStore.snapshot().copy(
                apiKey = apiKey.trim(),
                baseUrl = baseUrl.trim().ifEmpty { AppSettings.DEFAULT_BASE_URL },
            )
            runCatching { app.deepSeek.listModels(current) }
                .onSuccess { models ->
                    availableModels = models
                    settingsMessage = if (models.isEmpty()) {
                        "接口返回了空的模型列表"
                    } else {
                        "已获取 " + models.size + " 个模型，支持图像的：" +
                            models.filter { it.supportsImage }.joinToString("、") { it.id }
                    }
                }
                .onFailure { error ->
                    settingsMessage = describe(error)
                }
            modelsLoading = false
        }
    }

    // ------------------------------------------------------------------ AI 问答

    val chatEntries = mutableStateListOf<ChatEntry>()
    var chatStreaming by mutableStateOf(false)
        private set
    var chatError by mutableStateOf<String?>(null)
        private set

    private var chatJob: Job? = null

    fun sendChat(prompt: String, imageUri: Uri? = null) {
        val text = prompt.trim()
        if (text.isEmpty() && imageUri == null) return
        if (chatStreaming) return
        val image: Bitmap? = imageUri?.let { decodeBitmap(it) }

        chatEntries.add(ChatEntry(role = "user", text = if (text.isEmpty()) "[图片]" else text))
        chatEntries.add(ChatEntry(role = "assistant", text = "", streaming = true))
        chatStreaming = true
        chatError = null

        chatJob = viewModelScope.launch {
            try {
                val config = settingsStore.snapshot()
                if (!config.configured) {
                    chatError = "还没有配置 API Key，请先到「设置」里填写"
                    return@launch
                }

                val dataUrl = image?.let {
                    withContext(Dispatchers.Default) { ImageUtils.toDataUrl(it, ImageUtils.VISION_MAX_SIDE) }
                }

                val history = chatEntries
                    .filter { !it.streaming && it.text.isNotBlank() }
                    .dropLast(1)
                    .map { entry ->
                        if (entry.role == "user") Messages.user(entry.text) else Messages.assistant(entry.text)
                    }

                val messages = buildList {
                    add(Messages.system(config.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM }))
                    addAll(history.takeLast(MAX_HISTORY))
                    if (dataUrl != null) {
                        add(Messages.userWithImage(text.ifEmpty { Prompts.DEFAULT_ANSWER }, dataUrl, config.imageDetail))
                    } else {
                        add(Messages.user(text))
                    }
                }

                val model = if (dataUrl != null) config.visionModel else config.chatModel
                val answer = StringBuilder()
                val thinking = StringBuilder()

                app.deepSeek.chatStream(config, messages, model).collect { delta ->
                    delta.reasoning?.let { thinking.append(it) }
                    if (delta.content.isNotEmpty()) {
                        answer.append(delta.content)
                        updateLastAssistant(answer.toString(), thinking.toString(), streaming = true)
                    }
                }
                updateLastAssistant(answer.toString(), thinking.toString(), streaming = false)
            } catch (error: Exception) {
                chatError = describe(error)
                updateLastAssistant("", "", streaming = false)
            } finally {
                chatStreaming = false
            }
        }
    }

    fun stopChat() {
        chatJob?.cancel()
        chatJob = null
        chatStreaming = false
        updateLastAssistant(
            chatEntries.lastOrNull()?.text.orEmpty(),
            chatEntries.lastOrNull()?.thinking.orEmpty(),
            streaming = false,
        )
    }

    fun clearChat() {
        chatJob?.cancel()
        chatEntries.clear()
        chatError = null
        chatStreaming = false
    }

    private fun updateLastAssistant(text: String, thinking: String, streaming: Boolean) {
        val index = chatEntries.indexOfLast { it.role == "assistant" }
        if (index < 0) return
        chatEntries[index] = ChatEntry("assistant", text, thinking, streaming)
    }

    /** 相册里的图可能很大，先按视觉模型的最优边长采样，既省内存也省图片 token。 */
    private fun decodeBitmap(uri: Uri): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        var sample = 1
        while (options.outWidth / sample > ImageUtils.VISION_MAX_SIDE ||
            options.outHeight / sample > ImageUtils.VISION_MAX_SIDE
        ) {
            sample *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
        getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        }
    }.getOrNull()

    private fun describe(error: Throwable): String = when (error) {
        is ApiException -> error.message ?: "请求失败"
        else -> error.message ?: "未知错误"
    }

    private companion object {
        const val MAX_HISTORY = 12

        /** sheetIndex 取值 -1 表示「合并全部工作表」。 */
        const val ACTIVE_ALL_SHEETS = -1
    }
}
