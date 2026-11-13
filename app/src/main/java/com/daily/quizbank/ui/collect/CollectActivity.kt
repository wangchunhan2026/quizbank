package com.daily.quizbank.ui.collect

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import com.daily.quizbank.QuizBankApp
import com.daily.quizbank.data.ai.Messages
import com.daily.quizbank.data.ai.Pricing
import com.daily.quizbank.data.ai.Usage
import com.daily.quizbank.data.import.QuestionDraft
import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.data.prefs.AppSettings
import com.daily.quizbank.data.prefs.Prompts
import com.daily.quizbank.data.repo.QuestionRepository
import com.daily.quizbank.service.CollectBus
import com.daily.quizbank.service.CollectDraft
import com.daily.quizbank.service.PartDraft
import com.daily.quizbank.ui.theme.QuizBankTheme
import com.daily.quizbank.ui.theme.WarningAmber
import com.daily.quizbank.util.MultiPartSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 共用题干题默认摆几个小问的空位。
 *
 * 摆 2 个而不是 4 个：实测刷题软件是**一屏只显示一问**（用「第 1 问 / 第 2 问」切换），
 * 所以一次本来就只拍得到一问。想多收几问，点「再加一问」，再用「识别这一问」
 * 切过去把内容框回来。
 */
private const val SLOT_COUNT = 2

/**
 * 前几个小问是必填的。
 *
 * 只要第 1 问：一屏一问的拍法下一次只拿得到一问，逼着填满两问就存不了。
 */
private const val REQUIRED_PARTS = 1

/** 同一组共用题干题的 tags 前缀。用 tags 承载分组，就不必动表结构。 */
private const val GROUP_TAG_PREFIX = "共用题干:"

/**
 * 草稿里选项一行一个。
 *
 * 抽成常量只因为一个很实际的坑：这个文件里的 Kotlin 换行转义，在生成代码的那条链路上
 * 被吃过好几次（写进文件就成了字符串字面量中间断行）。写成常量之后只有这一处要盯着。
 */
private const val NEWLINE = "\n"

/**
 * 日志用的标签。
 *
 * 收录这条链路以前一行日志都没有，以至于「框选回来的内容到底填成什么样了」
 * 在真机上完全看不见 —— 排查「接续到底接没接上」只能靠用户在电话那头描述。
 */
private const val TAG = "CollectActivity"

/**
 * 收录题目。
 *
 * 为什么不是「拍完题顺手存一下」而要单独开一个界面：
 *
 *  1. **题干得能改**。OCR 出来的东西一定会有错字、会把刷题软件的界面文字混进来，
 *     不给人改的机会，收进去的就是一堆垃圾。
 *  2. **答案和解析要分开填**。这两个在来源那边是后一屏才出现的，而且用户想记的是
 *     *来源给的* 答案，不是我们猜的。
 *  3. **共用题干题要分流**。一个材料带好几个小问的题型，存法完全不同（每小问各一条），
 *     所以进来先让用户确认是不是这种题，再各走各的界面。
 *
 * 这个界面是独立的 Activity，但**不吃满屏幕**：窗口是半透明的，内容收在下面一张卡片里，
 * 上面露着原题，一边看一边填。
 */
class CollectActivity : ComponentActivity() {

    /** 用户已经明确表达了意愿（点了某个按钮、或按了返回）。 */
    private var finished = false

    /** 结局是否已经交回给悬浮窗服务。只允许交一次。 */
    private var published = false

    /** 用户的意思，等界面真的离开屏幕之后再送出去。 */
    private var pendingOutcome: CollectBus.Outcome? = null

    /**
     * 收尾只有这一个出口。
     *
     * 这里只记下意愿并结束自己，**真正的回话放到 onStop** —— 原因见那边。
     */
    private fun finishWith(outcome: CollectBus.Outcome) {
        if (finished) return
        finished = true
        pendingOutcome = outcome
        finish()
    }

    /**
     * 界面真的离开屏幕了，这时候才能把结局送出去。
     *
     * 之前是在 finishWith 里同步调 CollectBus.finish 的，踩了一个很难查的坑：
     * 服务收到「去截屏识别」之后会立刻抓一帧屏幕，而那一刻本界面**还在做退场动画**。
     * 抓到的就是「收录界面和题目界面各占一半」的中间帧。
     * onStop 的语义正是「本界面已经不可见了」，放在这里发事件，抓帧就一定发生在动画结束之后。
     */
    override fun onStop() {
        super.onStop()
        publishPending()
    }

    private fun publishPending() {
        if (published) return
        val outcome = pendingOutcome ?: return
        pendingOutcome = null
        published = true
        CollectBus.finish(outcome)
    }

    override fun onDestroy() {
        // 兜底：任何没走 onStop 的退出路径，也得给服务一个交代
        if (!published) {
            published = true
            CollectBus.finish(pendingOutcome ?: CollectBus.Outcome.Cancelled)
            pendingOutcome = null
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val draftJson = intent.getStringExtra(EXTRA_DRAFT)
        val captureTarget = intent.getStringExtra(EXTRA_CAPTURE_TARGET)
        val captureText = intent.getStringExtra(EXTRA_CAPTURE_TEXT)
        val captureAppend = intent.getBooleanExtra(EXTRA_CAPTURE_APPEND, false)

        setContent {
            QuizBankTheme {
                CollectSheet(
                    draftJson = draftJson,
                    captureTarget = captureTarget,
                    captureText = captureText,
                    captureAppend = captureAppend,
                    onCancel = { finishWith(CollectBus.Outcome.Cancelled) },
                    onSaved = { finishWith(CollectBus.Outcome.Cancelled) },
                    onFieldCapture = { json, target ->
                        finishWith(CollectBus.Outcome.FieldCapture(json, target))
                    },
                    onStepAside = { json -> finishWith(CollectBus.Outcome.StepAside(json)) },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DRAFT = "collect_draft"
        const val EXTRA_CAPTURE_TARGET = "collect_capture_target"
        const val EXTRA_CAPTURE_TEXT = "collect_capture_text"

        /**
         * 这一次框选到的文字要不要**接在**那一栏后面（而不是覆盖）。
         *
         * 用户在框选界面上点了「接着上次识别」才会是 true —— 题干翻屏才拍得完时，
         * 每拍一次都把上一段盖掉，等于白拍。
         */
        const val EXTRA_CAPTURE_APPEND = "collect_capture_append"
    }
}

/**
 * 收录面板。
 *
 * @param draftJson 上一次离开时那整份草稿；框选识别来回一趟靠它把内容带回来。
 * @param captureTarget/captureText 这一次框选识别出来的文字，以及它该填进哪个字段。
 * @param captureAppend 这次是「接着上一次往下接」，不是覆盖那一栏。
 */
@Composable
private fun CollectSheet(
    draftJson: String?,
    captureTarget: String?,
    captureText: String?,
    captureAppend: Boolean,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    onFieldCapture: (draftJson: String, target: String) -> Unit,
    onStepAside: (draftJson: String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as QuizBankApp
    val scope = rememberCoroutineScope()

    // 把这一次的框选结果并进草稿里。remember 保证只算一次 ——
    // 每重组一次就追加一遍，那段文字会越滚越多。
    // 顺带算一句「这次接上了什么」，摆在面板顶上：接续是悄悄发生的，
    // 不说一句用户分不清那颗开关起没起作用。
    val applied = remember {
        val base = CollectDraft.decode(draftJson) ?: CollectDraft()
        if (captureTarget != null && captureText != null) {
            val merged = base.applyCapture(captureTarget, captureText, captureAppend)
            val note = captureNote(base, merged, captureTarget, captureAppend)
            // 一行日志把「填进哪一栏、覆盖还是接续、那一栏从多少字变成多少字」全交代清楚，
            // 排查「接了没接上」不用再靠用户复述
            Log.i(
                TAG,
                "框选回填: " + captureTarget + " 接续=" + captureAppend +
                    " 那一栏 " + base.fieldOf(captureTarget).length +
                    " -> " + merged.fieldOf(captureTarget).length + " 字 | " + note,
            )
            merged to note
        } else {
            base to null
        }
    }
    val initialDraft = applied.first
    var draft by remember { mutableStateOf(initialDraft) }
    val mergeNote = applied.second

    // 滚动位置：进来先按草稿里记的位置起，等布局好了再落一次 ——
    // 光给初值不够，内容还没量出来时会被夹到 0。
    val scrollState = rememberScrollState(initial = initialDraft.scrollY)

    // 这一趟回来，是不是「刚识图把解析那一栏填上了」？
    // 解析在所有小问的最底下，不滚下去的话用户看到的是「点完没反应」——
    // 他站在表单上半截，而刚识别到的那段字在屏幕外面（2026-10-09 用户提的）。
    val filledAnalysis = captureTarget != null &&
        (captureTarget == CollectDraft.TARGET_ANALYSIS ||
            captureTarget.startsWith(CollectDraft.TARGET_PART_ANALYSIS))

    LaunchedEffect(Unit) {
        if (filledAnalysis) {
            // 内容还没量完时 maxValue 是 0，这时候滚过去等于没滚 —— 等它量出来
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            scrollState.scrollTo(scrollState.maxValue)
        } else if (initialDraft.scrollY > 0) {
            scrollState.scrollTo(initialDraft.scrollY)
        }
    }

    /** 离开面板之前把滚动位置一起带走，回来好落回原处。 */
    fun leavingDraft(): CollectDraft = draft.copy(scrollY = scrollState.value)

    // 一进来就问一次「是不是共用题干题」。草稿里已经记着 mode 的话不再问 ——
    // 从框选识别绕回来时不能又弹一次。
    var askingMode by remember { mutableStateOf(initialDraft.mode.isEmpty()) }

    var saving by remember { mutableStateOf(false) }
    var savedDialog by remember { mutableStateOf<QuestionRepository.CollectResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(true) }

    val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    var aiBusy by remember { mutableStateOf(false) }
    var aiNote by remember { mutableStateOf<String?>(null) }

    val isMulti = draft.mode == CollectDraft.MODE_MULTI

    /** 切模式时把已经识别到的内容搬过去，别让用户看到自己刚拍的东西没了。 */
    fun switchTo(mode: String) {
        askingMode = false
        if (draft.mode == mode) return
        draft = if (mode == CollectDraft.MODE_MULTI) toMultiDraft(draft) else toSingleDraft(draft)
        // 换了模式等于换了整张表单，原来的滚动位置没有意义了
        scope.launch { scrollState.scrollTo(0) }
        aiNote = null
        errorText = null
    }

    /**
     * 让 AI 现写一段解析，写进对应的输入框里。
     *
     * 关键在于**写进输入框而不是直接存库**：AI 说的话要经用户过目才算数。
     * 两个通道由用户自己挑，和结果面板上那两个按钮是同一回事。
     */
    fun runAi(free: Boolean, partIndex: Int) {
        if (aiBusy) return
        val part = if (partIndex >= 0) draft.parts.getOrNull(partIndex) else null
        val stemText = part?.body ?: draft.question
        val answerText = part?.answer ?: draft.answer
        val existingText = part?.analysis ?: draft.analysis
        if (stemText.isBlank() || answerText.isBlank()) {
            aiNote = "AI 得先知道题目和答案才讲得出依据，先把那一问的答案填上"
            return
        }
        aiBusy = true
        aiNote = null
        val base = existingText.trimEnd()
        // 共用题干题要把大题干一起发过去，否则第 2 问离开材料根本读不通
        val fullQuestion = if (partIndex >= 0) draft.sharedStem + "\n" + stemText else stemText
        fun applyAiText(text: String) {
            draft = if (partIndex < 0) {
                draft.copy(analysis = text)
            } else {
                draft.copy(
                    parts = draft.parts.mapIndexed { i, p ->
                        if (i == partIndex) p.copy(analysis = text) else p
                    },
                )
            }
        }
        scope.launch {
            val current = app.settings.snapshot()
            val channel = if (free) {
                current.copy(
                    apiKey = current.freeApiKey,
                    baseUrl = current.freeBaseUrl,
                    chatModel = current.freeModel,
                )
            } else {
                current
            }
            val model = channel.chatModel
            val messages = listOf(
                Messages.system(channel.systemPrompt.ifBlank { Prompts.DEFAULT_SYSTEM }),
                Messages.user(
                    Prompts.DEFAULT_EXPLAIN + "\n\n题目：\n" + fullQuestion +
                        "\n\n答案：" + answerText,
                ),
            )
            val buffer = StringBuilder()
            var usage: Usage? = null
            try {
                app.deepSeek.chatStream(channel, messages, model).collect { delta ->
                    delta.usage?.let { usage = it }
                    if (delta.content.isNotEmpty()) {
                        buffer.append(delta.content)
                        applyAiText(
                            if (base.isBlank()) buffer.toString() else base + "\n" + buffer,
                        )
                    }
                }
                val billed = usage
                if (billed != null) {
                    app.repository.recordUsage(
                        model = model,
                        promptTokens = billed.promptTokens,
                        completionTokens = billed.completionTokens,
                        cacheHitTokens = billed.cacheHitTokens,
                        usedImage = false,
                        costMicros = if (free) {
                            0L
                        } else {
                            Pricing.estimateMicros(
                                billed.promptTokens,
                                billed.completionTokens,
                                billed.cacheHitTokens,
                            )
                        },
                    )
                }
                aiNote = when {
                    buffer.isEmpty() -> "模型返回了空内容，可以再试一次"
                    free -> "免费通道 · " + model + " · 本次不花钱。不满意的直接改上面的字。"
                    else -> "深度通道 · " + model + "。不满意的直接改上面的字。"
                }
            } catch (error: Exception) {
                aiNote = "AI 解析失败：" + (error.message ?: "未知错误")
            }
            aiBusy = false
        }
    }

    /** 单题：题干和答案都要有。共用题干题：前两小问必须填完整。 */
    val canSave = if (isMulti) {
        draft.sharedStem.isNotBlank() &&
            draft.parts.take(REQUIRED_PARTS).all { it.body.isNotBlank() && it.answer.isNotBlank() } &&
            !saving
    } else {
        draft.question.isNotBlank() && draft.answer.isNotBlank() && !saving
    }
    val maxCardHeight = LocalConfiguration.current.screenHeightDp.dp * 0.62f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .imePadding(),
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(max = maxCardHeight),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                SheetHeader(
                    expanded = expanded,
                    isMulti = isMulti,
                    modeChosen = draft.mode.isNotEmpty(),
                    note = mergeNote,
                    onToggle = { expanded = !expanded },
                    onSwitchMode = {
                        if (draft.mode.isEmpty()) askingMode = true
                        else switchTo(if (isMulti) CollectDraft.MODE_SINGLE else CollectDraft.MODE_MULTI)
                    },
                    onStepAside = { onStepAside(leavingDraft().encode()) },
                    onCancel = onCancel,
                )

                if (expanded) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isMulti) {
                            SharedStemCard(
                                draft = draft,
                                onChange = { draft = it },
                                onCapture = {
                                    onFieldCapture(leavingDraft().encode(), CollectDraft.TARGET_STEM)
                                },
                            )
                            draft.parts.forEachIndexed { index, part ->
                                PartCard(
                                    index = index,
                                    part = part,
                                    required = index < REQUIRED_PARTS,
                                    removable = index >= REQUIRED_PARTS && draft.parts.size > REQUIRED_PARTS,
                                    settings = settings,
                                    aiBusy = aiBusy,
                                    onAi = { free -> runAi(free, index) },
                                    onChange = { updated ->
                                        draft = draft.copy(
                                            parts = draft.parts.mapIndexed { i, p ->
                                                if (i == index) updated else p
                                            },
                                        )
                                    },
                                    onRemove = {
                                        draft = draft.copy(
                                            parts = draft.parts
                                                .filterIndexed { i, _ -> i != index }
                                                .mapIndexed { i, p -> p.copy(label = "" + (i + 1)) },
                                        )
                                    },
                                    onCaptureBody = {
                                        onFieldCapture(
                                            leavingDraft().encode(),
                                            CollectDraft.partTarget(index),
                                        )
                                    },
                                    onCaptureOptions = {
                                        onFieldCapture(
                                            leavingDraft().encode(),
                                            CollectDraft.partOptionsTarget(index),
                                        )
                                    },
                                    onCaptureAnalysis = {
                                        onFieldCapture(
                                            leavingDraft().encode(),
                                            CollectDraft.partAnalysisTarget(index),
                                        )
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    draft = draft.copy(
                                        parts = draft.parts + PartDraft(label = "" + (draft.parts.size + 1)),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("再加一问") }
                            if (aiBusy) {
                                Text(
                                    "AI 正在写…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            aiNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            SingleQuestionCards(
                                draft = draft,
                                settings = settings,
                                aiBusy = aiBusy,
                                aiNote = aiNote,
                                onChange = { draft = it },
                                onCapture = { target ->
                                    onFieldCapture(leavingDraft().encode(), target)
                                },
                                onAi = { free -> runAi(free, -1) },
                            )
                        }

                        Text(
                            if (isMulti) {
                                "第 1 问必填，后面留空就不存。刷题软件一屏只显示一问的话：" +
                                    "点「再加一问」，再用「识别这一问」切到第 2 问框回来就好。" +
                                    "每一问都会把大题干拼在自己题干前面，以后单独拍其中一问也能对上。"
                            } else {
                                "解析可以留空 —— 以后拍到这题会显示「解析：暂无」，" +
                                    "想听讲解再点结果面板上的解析按钮。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        errorText?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = onCancel) { Text("取消") }

                            Button(
                                onClick = {
                                    errorText = null
                                    saving = true
                                    val snapshot = draft
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { save(app, snapshot) }
                                        }
                                        saving = false
                                        result.onSuccess { saved -> savedDialog = saved }
                                            .onFailure { errorText = it.message ?: "保存失败" }
                                    }
                                },
                                enabled = canSave,
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(16.dp).width(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("确定")
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        if (askingMode) {
            val looksMulti = remember(initialDraft.question) {
                val split = MultiPartSplitter.split(initialDraft.question)
                split != null && split.parts.size >= 2
            }
            AlertDialog(
                onDismissRequest = { switchTo(CollectDraft.MODE_SINGLE) },
                title = { Text("这道题是共用题干题吗？") },
                text = {
                    Text(
                        "一个材料或病例下面带好几道小问、每问各有自己的选项 —— 这类题要按共用题干收：" +
                            "大题干填一次，每个小问各存一条。\n\n" +
                            "刷题软件里用「第 1 问 / 第 2 问」切换的那种也算。" +
                            if (looksMulti) "\n\n（这道题看起来就是这种。）" else "",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { switchTo(CollectDraft.MODE_MULTI) }) { Text("是，共用题干") }
                },
                dismissButton = {
                    TextButton(onClick = { switchTo(CollectDraft.MODE_SINGLE) }) { Text("普通题") }
                },
            )
        }
    }

    savedDialog?.let { saved ->
        // 库里已经有的那几条：极相似的（一模一样 / 相似度够高 / **上次没收完的那半截**）
        // 加上被这次补全的半截旧条目。**删东西不替他决定** —— 这里只把两边摆出来，
        // 留谁、删谁由他点。
        val 旧的们 = saved.oldCandidates
        var 正在处理 by remember { mutableStateOf(false) }
        var 处理说明 by remember { mutableStateOf<String?>(null) }

        /**
         * 删完就关面板。删失败也不拦着他往下走 —— 旧条目还在，下次收同一题还会再问一次。
         */
        fun 处理(删旧: List<Long>, 删新: List<Long>, 说明: String) {
            if (正在处理) return
            正在处理 = true
            处理说明 = 说明
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        删旧.forEach { app.repository.deleteQuestionById(it) }
                        if (删新.isNotEmpty()) app.repository.deleteQuestionsByIds(删新)
                    }
                }
                savedDialog = null
                onSaved()
            }
        }

        if (旧的们.isEmpty()) {
            AlertDialog(
                onDismissRequest = { },
                // 重复的题不该说「感谢您」—— 那句话是给「又添了一道」说的
                title = { Text(if (saved.saved) "感谢您" else "这道题已经收录过") },
                text = { Text(saved.message) },
                confirmButton = {
                    TextButton(onClick = { savedDialog = null; onSaved() }) { Text("确定") }
                },
            )
        } else {
            Dialog(
                onDismissRequest = { },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (saved.saved) "库里已经有很像的题" else "这道题已经收录过",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "两边摆在一起，你点一下留哪条、删哪条。" +
                                "删掉的找不回来 —— 想留个后路，先去题库页导出一次。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("这次收进来的", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        saved.newStems.take(4).forEach { stem ->
                            Text(
                                "· " + stem.replace('\n', ' ').take(60),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (saved.newStems.size > 4) {
                            Text(
                                "· 还有 " + (saved.newStems.size - 4) + " 条…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("题库里已经有的 " + 旧的们.size + " 条", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Column(
                            modifier = Modifier
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            旧的们.forEach { 旧的 ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            旧的.reason,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = WarningAmber,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            旧的.stem.replace('\n', ' '),
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 4,
                                        )
                                        if (旧的.answer.isNotBlank() || 旧的.options.isNotEmpty()) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                "答案 " + 旧的.answer.ifBlank { "（空）" } +
                                                    " · 选项 " + 旧的.options.size + " 个" +
                                                    (if (旧的.analysis.isNotBlank()) " · 有解析" else ""),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                处理(旧的们.map { it.id }, emptyList(), "正在删旧的…")
                            },
                            enabled = !正在处理,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (正在处理) 处理说明 ?: "正在处理…"
                                else "留新的，删掉库里的 " + 旧的们.size + " 条",
                            )
                        }
                        if (saved.savedIds.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    处理(emptyList(), saved.savedIds, "正在撤掉刚收的…")
                                },
                                enabled = !正在处理,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("留旧的，把刚收的 " + saved.savedIds.size + " 条删掉")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = { savedDialog = null; onSaved() },
                            enabled = !正在处理,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("都留着，先不动")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 这一次「接着上次识别」到底接上了什么，给面板顶上回一句。
 *
 * 只有接续模式才有话说：覆盖是用户点按钮时就预期到的事，不用再报一遍。
 */
private fun captureNote(
    base: CollectDraft,
    merged: CollectDraft,
    target: String,
    append: Boolean,
): String? {
    if (!append) return null
    val before = base.fieldOf(target)
    val after = merged.fieldOf(target)
    // 一个字都没多出来，多半是用户滚回去把同一段又拍了一遍 —— 这一步本来就是防重复的，
    // 说清楚比让用户以为「识别没反应」强。
    if (after == before) return "这次识别到的内容那一栏里已经有了，没有重复添加。"
    val delta = after.length - before.length
    return if (delta > 0) {
        "接着上次往下接了：这一栏多了 " + delta + " 个字。"
    } else {
        "接着上次整理了一遍这一栏。"
    }
}

/** 把草稿存进题库：单题一条，共用题干题每小问一条。 */
private suspend fun save(
    app: QuizBankApp,
    draft: CollectDraft,
): QuestionRepository.CollectResult {
    if (draft.mode != CollectDraft.MODE_MULTI) {
        return app.repository.saveCollected(
            stem = draft.question.trim(),
            options = parseOptionLines(draft.options),
            answer = draft.answer.trim(),
            analysis = draft.analysis.trim(),
        )
    }
    // 只存「题干和答案都填了」的小问。留空的那些是没用到的小问位，不是漏填。
    val filled = draft.parts.filter { it.body.isNotBlank() && it.answer.isNotBlank() }
    return app.repository.saveCollectedMulti(
        shared = draft.sharedStem.trim(),
        parts = filled.map { part ->
            // 题型不在这儿定：每一小问的选项和答案都不一样，
            // 由 saveCollectedMulti 逐条调 QuestionParser.inferType 推（这里写死过 SINGLE，
            // 结果同一组里的多选题全被贴上了单选标签）。
            QuestionDraft(
                stem = part.body.trim(),
                options = parseOptionLines(part.options),
                answer = part.answer.trim(),
                analysis = part.analysis.trim(),
            )
        },
        groupTag = GROUP_TAG_PREFIX + draft.sharedStem.replace('\n', ' ').take(16),
    )
}

/**
 * 选项在草稿里一行一个（「A. 是否与自己有利害关系」）。
 *
 * 入库要的是列表，而且全库统一成「字母 + . + 正文」这一种写法 —— 用户可能手打成
 * 「A、xxx」「(A) xxx」，也可能一行里挤了好几个选项。splitOptionBlob 干的就是这件事，
 * 导入那条路用的也是它，口径一致才不会出现「导入的题能还原选项、收录的不能」。
 */
private fun parseOptionLines(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else QuestionParser.splitOptionBlob(raw)

/**
 * 答案输入框的即时处理：**敲进去就转成大写**。
 *
 * 用户不该为「b 还是 B」操心，而且在手机键盘上切大小写本身就很麻烦。
 * 中文在这里不受影响（「对」「正确」转完还是原样），所以判断题那一套写法照旧。
 *
 * 去分隔符、去重排序、把「对 / √」理成「正确」这些放到入库时做（[QuestionParser.normalizeAnswer]）——
 * 边打字边吞逗号会让用户以为按键坏了。
 */
private fun asAnswerInput(raw: String): String = raw.uppercase()

/** 普通题 → 共用题干题。已经识别到的内容搬到小问里去，别让用户看到刚拍的东西没了。 */
private fun toMultiDraft(draft: CollectDraft): CollectDraft {
    val split = MultiPartSplitter.split(draft.question)
    // 认出来几问就用几问。这里以前要求「至少认到两问」才肯用拆分结果 —— 而真机上
    // 一屏只有一问，于是永远走下面那条兜底，把**整段原文**（材料 + 第1问）塞进问题 1。
    val parts = ArrayList<PartDraft>()
    if (split != null) {
        // 拆分器给出的小问正文里还带着自己的选项，逐条再拆一次
        split.parts.forEachIndexed { index, part ->
            val (body, options) = QuestionParser.splitTrailingOptions(part.body)
            parts.add(
                PartDraft(
                    label = "" + (index + 1),
                    body = body,
                    options = options.joinToString(NEWLINE),
                ),
            )
        }
    } else if (draft.question.isNotBlank()) {
        // 单题那一栏本来就是题干/选项分开放的，原样搬过去
        parts.add(PartDraft(label = "1", body = draft.question, options = draft.options))
    }
    while (parts.size < SLOT_COUNT) parts.add(PartDraft(label = "" + (parts.size + 1)))
    return draft.copy(
        mode = CollectDraft.MODE_MULTI,
        sharedStem = split?.shared ?: "",
        // 单题那栏填的选项跟着第一问走，别丢
        options = "",
        parts = parts,
    )
}

/** 共用题干题 → 普通题。把题干和小问拼回一整段，同样是为了不丢内容。 */
private fun toSingleDraft(draft: CollectDraft): CollectDraft {
    val merged = StringBuilder()
    val options = ArrayList<String>()
    if (draft.sharedStem.isNotBlank()) merged.append(draft.sharedStem.trim())
    draft.parts.forEach { part ->
        if (part.body.isNotBlank()) {
            if (merged.isNotEmpty()) merged.append("\n\n")
            merged.append(part.body.trim())
        }
        if (part.options.isNotBlank()) options.add(part.options.trim())
    }
    return draft.copy(
        mode = CollectDraft.MODE_SINGLE,
        question = if (merged.isNotEmpty()) merged.toString() else draft.question,
        // 单题只有一栏选项，多问合并时按顺序摞在一起（选项行自带 A/B/C，不会认错）
        options = if (options.isNotEmpty()) options.joinToString(NEWLINE) else draft.options,
    )
}

/** 单题的三个卡片。 */
@Composable
private fun SingleQuestionCards(
    draft: CollectDraft,
    settings: AppSettings,
    aiBusy: Boolean,
    aiNote: String?,
    onChange: (CollectDraft) -> Unit,
    onCapture: (String) -> Unit,
    onAi: (Boolean) -> Unit,
) {
    CollectCard("题干") {
        FieldBody(
            value = draft.question,
            onChange = { onChange(draft.copy(question = it)) },
        )
        Spacer(Modifier.height(8.dp))
        // 一次框、分开存：框下来的通常是「题干 + A. xx + B. xx…」一整块，
        // applyCapture 会当场把它拆进下面那一栏。想只重框题干也可以，拆不动就整块当题干。
        CaptureButton("识别题干") { onCapture(CollectDraft.TARGET_QUESTION) }
    }

    CollectCard("选项（选填）") {
        FieldBody(
            value = draft.options,
            placeholder = "一行一个，比如「A. 是否与自己有利害关系」",
            onChange = { onChange(draft.copy(options = it)) },
        )
        Spacer(Modifier.height(8.dp))
        CaptureButton("只识别选项") { onCapture(CollectDraft.TARGET_OPTIONS) }
    }

    CollectCard("正确答案") {
        OutlinedTextField(
            value = draft.answer,
            onValueChange = { onChange(draft.copy(answer = asAnswerInput(it))) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：B  或  AB  或  正确") },
            supportingText = {
                Text("字母大小写都行，敲进去就是大写。多选写 AB 或 A、B 都认；判断题写「对 / 错」或「正确 / 错误」")
            },
        )
    }

    CollectCard("解析（选填）") {
        OutlinedTextField(
            value = draft.analysis,
            onValueChange = { onChange(draft.copy(analysis = it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            placeholder = { Text("留空也可以，或者用下面的按钮去屏幕上识别一段") },
        )
        Spacer(Modifier.height(8.dp))
        CaptureButton("识别解析") { onCapture(CollectDraft.TARGET_ANALYSIS) }
        Spacer(Modifier.height(10.dp))
        if (settings.freeConfigured || settings.configured) {
            Text(
                "原题没有解析？也可以让 AI 写一段 —— 写完会填进上面这个框，" +
                    "先过目、能改，确认了再存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (settings.freeConfigured) {
                    OutlinedButton(
                        onClick = { onAi(true) },
                        enabled = !aiBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("免费解析") }
                }
                if (settings.configured) {
                    OutlinedButton(
                        onClick = { onAi(false) },
                        enabled = !aiBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("深度解析") }
                }
            }
            if (aiBusy) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "AI 正在写…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "想让 AI 替你写解析，先到「设置」里填一个 API Key。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        aiNote?.let { note ->
            Spacer(Modifier.height(6.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 共用题干题的大题干。 */
@Composable
private fun SharedStemCard(
    draft: CollectDraft,
    onChange: (CollectDraft) -> Unit,
    onCapture: () -> Unit,
) {
    CollectCard("题干（材料）") {
        FieldBody(
            value = draft.sharedStem,
            onChange = { onChange(draft.copy(sharedStem = it)) },
        )
        Spacer(Modifier.height(8.dp))
        CaptureButton("识别题干") { onCapture() }
    }
}

/** 共用题干题里的一个小问：正文（含选项）+ 答案。 */
@Composable
private fun PartCard(
    index: Int,
    part: PartDraft,
    required: Boolean,
    removable: Boolean,
    settings: AppSettings,
    aiBusy: Boolean,
    onChange: (PartDraft) -> Unit,
    onRemove: () -> Unit,
    onCaptureBody: () -> Unit,
    onCaptureOptions: () -> Unit,
    onCaptureAnalysis: () -> Unit,
    onAi: (Boolean) -> Unit,
) {
    CollectCard("问题 " + (index + 1) + if (required) "" else "（选填）") {
        FieldBody(
            value = part.body,
            placeholder = "这一问的题干（选项单独填在下面）",
            onChange = { onChange(part.copy(body = it)) },
        )
        Spacer(Modifier.height(8.dp))
        CaptureButton("识别这一问") { onCaptureBody() }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = part.options,
            onValueChange = { onChange(part.copy(options = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("问题 " + (index + 1) + " 的选项（选填）") },
            placeholder = { Text("一行一个，比如「A. 是否与自己有利害关系」") },
        )
        Spacer(Modifier.height(8.dp))
        CaptureButton("只识别选项") { onCaptureOptions() }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = part.answer,
            onValueChange = { onChange(part.copy(answer = asAnswerInput(it))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("问题 " + (index + 1) + " 的答案") },
            placeholder = { Text("例如：B  或  AB  或  正确") },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = part.analysis,
            onValueChange = { onChange(part.copy(analysis = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("问题 " + (index + 1) + " 的解析（选填）") },
            minLines = 2,
            placeholder = { Text("留空也行；以后在题库里能筛出「无解析」的题再补") },
        )
        Spacer(Modifier.height(8.dp))
        CaptureButton("识别解析") { onCaptureAnalysis() }
        if (settings.freeConfigured || settings.configured) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (settings.freeConfigured) {
                    OutlinedButton(
                        onClick = { onAi(true) },
                        enabled = !aiBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("免费解析") }
                }
                if (settings.configured) {
                    OutlinedButton(
                        onClick = { onAi(false) },
                        enabled = !aiBusy,
                        modifier = Modifier.weight(1f),
                    ) { Text("深度解析") }
                }
            }
        }
        if (removable) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onRemove) { Text("删掉这一问") }
        }
    }
}

/**
 * 一段正文输入框。
 *
 * 以前这里有个「修改 / 改好了」的开关，默认只读 —— 想法是「顺手就能存完的题不该逼人
 * 先进编辑态」。真机上这个设计是失败的：开关在卡片最下面，内容一长就滚出屏幕外，
 * 用户看着那一段认错的文字、点它，什么也不会发生，只能以为界面坏了。
 * 现在一律可输入，少一个状态、少一处困惑。
 */
@Composable
private fun FieldBody(
    value: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
    )
}

/** 「屏幕识别」按钮：把面板收掉，去屏幕上框一段，回来填进对应的字段。 */
@Composable
private fun CaptureButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label + "（框选屏幕，或去翻原题）")
    }
}

/**
 * 卡片顶上的那一条：标题 + 模式切换 + 收起/展开 + 关闭。
 *
 * 整条都能点，不只是那个箭头 —— 这是「我要看原题」最顺手的落点，
 * 让人去瞄准一个 24dp 的图标属于自找麻烦。
 */
@Composable
private fun SheetHeader(
    expanded: Boolean,
    isMulti: Boolean,
    modeChosen: Boolean,
    note: String?,
    onToggle: () -> Unit,
    onSwitchMode: () -> Unit,
    onStepAside: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isMulti) "收录共用题干题" else "收录题目",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "取消")
            }
        }
        // 「接着上次识别」接上了什么，就写在这儿。摆在标题下面而不是可滚动的内容里：
        // 从框选绕回来时面板会落回上次的滚动位置，写在内容里多半在屏幕外面。
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onStepAside) { Text("去翻原题") }
            TextButton(onClick = onSwitchMode) {
                Text(
                    when {
                        !modeChosen -> "选模式"
                        isMulti -> "改成普通题"
                        else -> "改成共用题干题"
                    },
                )
            }
        }
    }
}

@Composable
private fun CollectCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
