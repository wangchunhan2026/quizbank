package com.daily.quizbank.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daily.quizbank.data.import.QuestionDraft
import com.daily.quizbank.data.repo.DuplicateDraft
import com.daily.quizbank.ui.DedupChoice
import com.daily.quizbank.ui.ImportUiState
import com.daily.quizbank.ui.QuizBankViewModel
import com.daily.quizbank.ui.theme.WarningAmber
import com.daily.quizbank.util.AnswerText
import com.daily.quizbank.util.DedupPolicy
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: QuizBankViewModel,
    onBack: () -> Unit,
    onFinished: (Long) -> Unit,
) {
    val state = viewModel.importState
    val banks by viewModel.repository.banks.collectAsStateWithLifecycle(initialValue = emptyList())

    var targetBankId by remember { mutableStateOf<Long?>(viewModel.preferredBankId) }
    var newBankName by remember { mutableStateOf("") }
    var newBankSubject by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.startImport(uri)
    }

    LaunchedEffect(banks, state) {
        if (targetBankId == null && banks.isNotEmpty()) targetBankId = banks.first().bank.id
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入题库") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.resetImport(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        // 导入按钮固定在底部常驻：题目列表有一千多条，按钮埋在列表末尾
        // 等于逼着用户一路滑到底，这是实打实的体验问题。
        bottomBar = {
            val current = state
            if (current is ImportUiState.Ready) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "已勾选 " + current.selected.size + " 道题",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val targetName = targetBankId?.let { id ->
                                banks.firstOrNull { it.bank.id == id }?.bank?.name
                            } ?: newBankName.trim().ifEmpty { "未命名题库" }
                            Text(
                                "导入到「" + targetName + "」",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                val name = targetBankId?.let { id ->
                                    banks.firstOrNull { it.bank.id == id }?.bank?.name
                                } ?: newBankName.trim().ifEmpty { "导入题库" }
                                viewModel.commitImport(name, newBankSubject)
                            },
                            enabled = current.selected.isNotEmpty() &&
                                (targetBankId != null || newBankName.isNotBlank()),
                        ) {
                            Text("导入")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val current = state) {
                ImportUiState.Idle -> PickPrompt(open = { picker.launch(IMPORT_MIME_TYPES) })

                is ImportUiState.Loading -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(current.fileName, style = MaterialTheme.typography.bodyMedium)
                }

                is ImportUiState.Failed -> Centered {
                    Text("导入失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { picker.launch(IMPORT_MIME_TYPES) }) { Text("重新选择文件") }
                }

                is ImportUiState.Ready -> {
                    val drafts = current.drafts
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            InfoCard(
                                title = current.fileName,
                                lines = buildList {
                                    add("识别到 " + drafts.size + " 道题，已勾选 " + current.selected.size + " 道")
                                    add(current.mapping.describe())
                                    current.warnings.forEach { add("提示：" + it) }
                                },
                                warning = current.warnings.isNotEmpty(),
                            )
                        }

                        item {
                            Column {
                                Text("导入到哪个题库", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    banks.forEach { item ->
                                        FilterChip(
                                            selected = targetBankId == item.bank.id,
                                            onClick = { targetBankId = item.bank.id },
                                            label = { Text(item.bank.name) },
                                        )
                                    }
                                    FilterChip(
                                        selected = targetBankId == null,
                                        onClick = { targetBankId = null },
                                        label = { Text("新建题库") },
                                    )
                                }
                                if (targetBankId == null) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = newBankName,
                                        onValueChange = { newBankName = it },
                                        label = { Text("新题库名称") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = newBankSubject,
                                        onValueChange = { newBankSubject = it },
                                        label = { Text("科目（可留空）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        if (current.sheets.size > 1) {
                            item {
                                Column {
                                    Text("选择工作表", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        // 「全部」排在最前：一份文件里往往同时有单选、判断、多选几张表，
                                        // 强制一张一张导是没必要的麻烦。
                                        FilterChip(
                                            selected = current.sheetIndex == -1,
                                            onClick = { viewModel.selectSheet(-1) },
                                            label = {
                                                Text(
                                                    "全部 (" +
                                                        current.sheets.sumOf { it.dataRowCount } + ")"
                                                )
                                            },
                                        )
                                        current.sheets.forEachIndexed { index, sheet ->
                                            if (sheet.dataRowCount <= 1 && current.sheets.size > 1) return@forEachIndexed
                                            FilterChip(
                                                selected = index == current.sheetIndex,
                                                onClick = { viewModel.selectSheet(index) },
                                                label = { Text(sheet.name + " (" + sheet.dataRowCount + ")") },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("题目预览", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { viewModel.selectAllDrafts(true) }) { Text("全选") }
                                    TextButton(onClick = { viewModel.selectAllDrafts(false) }) { Text("全不选") }
                                }
                            }
                        }

                        if (targetBankId == null) {
                            item {
                                Column {
                                    OutlinedTextField(
                                        value = newBankName,
                                        onValueChange = { newBankName = it },
                                        label = { Text("新题库名称") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = newBankSubject,
                                        onValueChange = { newBankSubject = it },
                                        label = { Text("科目（可留空）") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        itemsIndexed(drafts) { index, draft ->
                            DraftRow(
                                index = index,
                                checked = index in current.selected,
                                stem = draft.stem,
                                options = draft.options,
                                answer = draft.answer,
                                type = draft.type,
                                onToggle = { viewModel.toggleDraft(index) },
                            )
                        }

                    }
                }

                is ImportUiState.Duplicates -> DedupChoicePane(
                    state = current,
                    onChoose = { viewModel.chooseDedup(it) },
                    onCancel = { viewModel.cancelDedup() },
                )

                is ImportUiState.Reviewing -> ReviewPane(
                    state = current,
                    onSelect = { side -> viewModel.reviewSelect(side) },
                    onConfirm = { viewModel.reviewConfirm() },
                    onRest = { keepNew -> viewModel.reviewRest(keepNew) },
                )

                is ImportUiState.Done -> Centered {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("导入完成", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "新增 " + current.summary.inserted + " 道，跳过重复 " +
                            current.summary.duplicates + " 道，跳过空行 " + current.summary.blank + " 行。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 顶掉是**删数据**，和「跳过」不是一回事：跳过只是这次没收，顶掉是库里那条真没了。
                    // 删掉的东西必须单独报一句，还要把题干列出来 —— 这是这一条唯一能看见的地方。
                    if (current.summary.replaced > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "顶掉了题库里原有的 " + current.summary.replaced + " 道题" +
                                "（你在核对页选了「保留新导入的这条」）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarningAmber,
                        )
                    }

                    // 近似的那几条按「两条都留」处理，等于一个字都没动他的题库，说一句让他放心
                    if (current.summary.keptBoth > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "另有 " + current.summary.keptBoth + " 道只是高度相似的重复题，" +
                                "按「两条都留」处理了，原来的题一道没动。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 「跳过 12 道重复题」这句话用户没法凭空相信，把具体是哪些列出来让他核对
                    if (current.summary.duplicateSamples.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "被判为重复而跳过的题目：",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        current.summary.duplicateSamples.take(8).forEach { stem ->
                            Text(
                                "· " + stem.take(56) + if (stem.length > 56) "…" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (current.summary.duplicates > 8) {
                            Text(
                                "（还有 " + (current.summary.duplicates - 8) + " 道未列出）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (current.summary.replacedSamples.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("被顶掉的旧题（库里那几条已经删了）：", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        current.summary.replacedSamples.take(8).forEach { stem ->
                            Text(
                                "· " + stem.take(56) + if (stem.length > 56) "…" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarningAmber,
                            )
                        }
                        if (current.summary.replaced > 8) {
                            Text(
                                "（还有 " + (current.summary.replaced - 8) + " 道未列出）",
                                style = MaterialTheme.typography.bodyMedium,
                                color = WarningAmber,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row {
                        OutlinedButton(onClick = { viewModel.resetImport() }) { Text("再导一个") }
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { onFinished(current.bankId) }) {
                            Text("去看题库")
                        }
                    }

                    // 导入完就到此为止的人占多数。以前这一屏只有「再导一个 / 去看题库」，
                    // 想结束这次导入就只能去够左上角那个返回小箭头 —— 所以补一个「完成」。
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.resetImport()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

@Composable
private fun PickPrompt(open: () -> Unit) {
    Centered {
        Icon(
            Icons.Default.Upload,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(14.dp))
        Text("选择要导入的题库文件", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 .xlsx / .xls / .docx / .csv / .txt。\n" +
                "旧版 .doc 请先用 Word 另存为 .docx。\n\n" +
                "微信、QQ 聊天记录里的文件不会出现在系统选择器里，" +
                "请在聊天中长按文件 →「用其他应用打开」→ 选择「题库助手」。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = open) { Text("选择文件") }
    }
}


/**
 * 重复题的「另一边」：优先是题库里已有的那条，其次才是文件里更早出现的那条。
 * 核对时要看的就是这两边的答案对不对得上。
 */
private class DupOther(
    val stem: String,
    val answer: String,
    val options: List<String>,
    val analysis: String,
    /** 卡片顶上那句话：这条到底在哪儿（这个题库 / 别的题库 / 文件里更早那条）。 */
    val title: String,
    val index: Int,
)

/**
 * 摆哪条当对照项、标题怎么写。
 *
 * **别的题库里那条必须把库名写出来**：这次导入删不掉它，用户唯一能做的是「这条不入库」；
 * 不说清是哪个库，他会当成这个库里已经有的题。
 */
private fun otherSide(drafts: List<QuestionDraft>, dup: DuplicateDraft): DupOther? {
    dup.existingStem?.let { stem ->
        return DupOther(
            stem = stem,
            answer = dup.existingAnswer.orEmpty(),
            options = dup.existingOptions,
            analysis = dup.existingAnalysis,
            title = if (dup.otherBank) {
                "「" + dup.existingBankName + "」题库里已有的那条（选它＝这条不入库）"
            } else {
                "题库里已有的那条（选它＝新导入的这条不入库，库里那条不动）"
            },
            index = -1,
        )
    }
    drafts.getOrNull(dup.earlierIndex)?.let { earlier ->
        return DupOther(
            stem = earlier.stem,
            answer = earlier.answer,
            options = earlier.options,
            analysis = earlier.analysis,
            title = "文件里更早出现的那条（第 " + (dup.earlierIndex + 1) + " 道）",
            index = dup.earlierIndex,
        )
    }
    return null
}

/** 两边的答案或选项是否对不上。对不上的那些才是真正需要人来定的。 */
private fun conflicts(drafts: List<QuestionDraft>, dup: DuplicateDraft): Boolean {
    val other = otherSide(drafts, dup) ?: return false
    val mine = drafts.getOrNull(dup.index) ?: return false
    return other.answer.trim() != mine.answer.trim() || other.options != mine.options
}

/** 发现重复题后的三选一。 */
@Composable
private fun DedupChoicePane(
    state: ImportUiState.Duplicates,
    onChoose: (DedupChoice) -> Unit,
    onCancel: () -> Unit,
) {
    val conflictCount = remember(state) { state.duplicates.count { conflicts(state.drafts, it) } }
    val exactCount = state.duplicates.size - state.nearCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("检测到 " + state.duplicates.size + " 道重复题", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "其中 " + exactCount + " 道一模一样（题干去掉空白、转小写后完全相同，标点保留）；" +
                if (state.nearCount > 0) {
                    "另 " + state.nearCount + " 道只是高度相似（相似度 88% 以上，未必是同一道题）。"
                } else {
                    "没有只是相似的那种。"
                } +
                "本次要入库 " + state.drafts.size + " 道题。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.otherBankCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "其中 " + state.otherBankCount + " 道的对照题在别的题库里。" +
                    "这次导入删不掉别的库里的题，想避免两个库各存一份，就在核对页选「这条不入库」。",
                style = MaterialTheme.typography.bodyMedium,
                color = WarningAmber,
            )
        }

        if (conflictCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "其中 " + conflictCount + " 组两边的答案或选项对不上。" +
                    "这种只能人来定 —— 留下哪一条，完全取决于它在文件里的先后顺序，" +
                    "先出现的那条未必是对的。",
                style = MaterialTheme.typography.bodyMedium,
                color = WarningAmber,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(onClick = { onChoose(DedupChoice.REVIEW) }, modifier = Modifier.fillMaxWidth()) {
            Text("逐一核对（" + state.duplicates.size + " 道）")
        }
        Spacer(Modifier.height(6.dp))
        ChoiceHint(
            "把两边的题干、选项、答案摆在一起，你决定留哪条。嫌多可以随时「剩下都保留…」，" +
                "但标着「只是相似」的那几条不跟着走，得各自点一下。"
        )

        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = { onChoose(DedupChoice.SKIP) }, modifier = Modifier.fillMaxWidth()) {
            Text("重复的直接舍去")
        }
        Spacer(Modifier.height(6.dp))
        ChoiceHint("不看题目：一模一样的那些一律不入库；只是相似的那几条照常入库（它们未必是同一道题）。")

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onChoose(DedupChoice.IMPORT_ALL) }, modifier = Modifier.fillMaxWidth()) {
            Text("全部导入")
        }
        Spacer(Modifier.height(6.dp))
        ChoiceHint("重复题也入库。题库里会出现同一道题的两份拷贝，两份答案不同时，拍题会采用先入库的那条。")

        Spacer(Modifier.height(22.dp))
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("返回预览") }
    }
}

@Composable
private fun ChoiceHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 确认按钮上那句话：选了哪条、这一按会发生什么，得说全。 */
private fun confirmLabel(dup: DuplicateDraft, keep: DedupPolicy.Keep?, last: Boolean): String {
    if (keep == null) return "先点一下你要保留的那一张"
    val tail = if (last) "，完成导入" else "，下一道"
    val head = when (keep) {
        DedupPolicy.Keep.OLD -> "保留原来那条（这条不再导入）"
        DedupPolicy.Keep.BOTH -> "两条都留"
        DedupPolicy.Keep.NEW -> when {
            dup.inBank -> "保留新导入的这条（顶掉库里那条）"
            dup.otherBank -> "保留新导入的这条（两个题库各存一份）"
            dup.earlierIndex >= 0 -> "保留新导入的这条（丢文件里更早那份）"
            else -> "保留新导入的这条"
        }
    }
    return head + tail
}

/**
 * 逐题核对：左右摆开，一眼看出两边的答案是否一致。
 *
 * 默认选哪条是 [DedupPolicy.defaultKeep] 算的（近似 → 两条都留；答案对不上 → 不预设；
 * 库里那条还带着解析 → 留原来那条），这里只负责把结论摆出来让他改。
 */
@Composable
private fun ReviewPane(
    state: ImportUiState.Reviewing,
    onSelect: (DedupPolicy.Keep) -> Unit,
    onConfirm: () -> Unit,
    onRest: (Boolean) -> Unit,
) {
    val dup = state.current
    val mine = state.drafts.getOrNull(dup.index)
    val other = otherSide(state.drafts, dup)
    val mismatch = conflicts(state.drafts, dup)
    val chosen = state.keep
    val last = state.index + 1 >= state.total
    val lostFields = if (dup.inBank) {
        DedupPolicy.extraFields(
            dup.counterpart,
            DedupPolicy.Newcomer(
                answer = mine?.answer.orEmpty(),
                options = mine?.options.orEmpty(),
                analysis = mine?.analysis.orEmpty(),
                tags = mine?.tags.orEmpty(),
            ),
        )
    } else {
        emptyList()
    }
    val nearLeft = state.duplicates.drop(state.index).any { it.near }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "核对重复题  " + (state.index + 1) + " / " + state.total,
            style = MaterialTheme.typography.titleMedium,
        )
        if (dup.near) {
            Spacer(Modifier.height(8.dp))
            Text(
                "这两条只是相似：" + (dup.score * 100).roundToInt() + "%（不是一模一样，" +
                    "库里本来就可能有只差一两个字、答案却不同的两道题）",
                color = WarningAmber,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (mismatch) {
            Text("⚠ 两边答案或选项不一致", color = WarningAmber, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }

        Text(
            "这两份里留哪一份？点一下你要保留的那张卡片。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        SelectableDupCard(
            selected = chosen == DedupPolicy.Keep.NEW,
            onClick = { onSelect(DedupPolicy.Keep.NEW) },
            title = "新导入的这条（文件里第 " + (dup.index + 1) + " 道）",
            stem = mine?.stem.orEmpty(),
            answer = mine?.answer.orEmpty(),
            options = mine?.options.orEmpty(),
            analysis = mine?.analysis.orEmpty(),
            conflict = mismatch,
        )

        Spacer(Modifier.height(10.dp))

        if (other != null) {
            SelectableDupCard(
                selected = chosen == DedupPolicy.Keep.OLD,
                onClick = { onSelect(DedupPolicy.Keep.OLD) },
                title = other.title,
                stem = other.stem,
                answer = other.answer,
                options = other.options,
                analysis = other.analysis,
                conflict = mismatch,
            )
            Spacer(Modifier.height(10.dp))
        }

        ChoiceRow(
            selected = chosen == DedupPolicy.Keep.BOTH,
            onClick = { onSelect(DedupPolicy.Keep.BOTH) },
            title = "两条都留",
            hint = "两份都留着。近似的那种多半就是两道不同的题，拿不准时点这个最安全。",
        )

        // 顶掉是删数据：那条旧题身上还带着文件里没有的东西时，先把话说清楚再让他点
        if (lostFields.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "⚠ 题库里那条还带着：" + lostFields.joinToString("、") +
                    "（文件里这条没有）。选「保留新导入的这条」会把它们一起删掉，" +
                    "想留着就点上面那张「题库里已有的那条」。",
                color = WarningAmber,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(16.dp))
        if (chosen == null) {
            Text(
                "两边答案或选项不一样，先点一下你要保留的那一张",
                color = WarningAmber,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = onConfirm,
            enabled = chosen != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(confirmLabel(dup, chosen, last))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onRest(true) }) { Text("剩下都保留新导入的") }
            TextButton(onClick = { onRest(false) }) { Text("剩下都保留原来那条") }
        }
        if (nearLeft) {
            Text(
                "标着「只是相似」的那几条不跟着这两个按钮走 —— 它们未必是同一道题，会按「两条都留」留着。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 第三个选择：两条都留。摆成和上面两张卡片一样的样子，才不会被当成一句说明文字。 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    hint: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelectableDupCard(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    stem: String,
    answer: String,
    options: List<String>,
    analysis: String,
    conflict: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (conflict) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onClick)
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(stem, style = MaterialTheme.typography.bodyMedium)
            val letters = answer.trim().uppercase().filter { it in 'A'..'H' }
            options.forEachIndexed { index, option ->
                val isAnswer = AnswerText.letterOf(index) in letters
                Text(
                    AnswerText.optionLine(index, option),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isAnswer) FontWeight.Bold else null,
                    color = if (isAnswer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (answer.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "答案：" + AnswerText.resolve(answer, options),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // 有解析的一边要摆出来：解析往往只存在于题库那条里，顶掉它就没有了
            if (analysis.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "解析：" + analysis.take(80) + if (analysis.length > 80) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
        ) { content() }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>, warning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warning && index == lines.lastIndex) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DraftRow(
    index: Int,
    checked: Boolean,
    stem: String,
    options: List<String>,
    answer: String,
    type: String,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(end = 12.dp)) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.padding(top = 14.dp, bottom = 14.dp)) {
                Text(
                    text = (index + 1).toString() + ". " + stem,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (options.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = options.take(4).joinToString("  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = type + if (answer.isBlank()) "" else " · 答案 " + answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (answer.isBlank()) WarningAmber else MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private val IMPORT_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    // 很多网盘 / 文件管理器给出的 MIME 不标准，兜底放开全部
    "*/*",
)
