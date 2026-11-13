package com.daily.quizbank.ui.screen

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daily.quizbank.data.db.BankWithCount
import com.daily.quizbank.data.repo.QuestionRepository
import com.daily.quizbank.ui.QuizBankViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankListScreen(
    viewModel: QuizBankViewModel,
    overlayGranted: Boolean,
    floatingRunning: Boolean,
    onToggleFloating: (Boolean) -> Unit,
    onOpenBank: (Long) -> Unit,
    onCreateBank: (Long) -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val banks by viewModel.repository.banks.collectAsStateWithLifecycle(initialValue = emptyList())
    val listState = rememberLazyListState()

    var showCreate by remember { mutableStateOf(false) }
    var newBankName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<BankWithCount?>(null) }

    // ---- 拖拽合并 ----
    // 长按卡片把它拎起来，拖到另一张卡片上松手就能合并。这里只记「正在拖谁、拖了多远、
    // 现在压在谁身上」；真正算重复题要读两个库，放到落下之后再做。
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDelta by remember { mutableStateOf(0f) }
    var dragAnchor by remember { mutableStateOf(0) }
    var dropId by remember { mutableStateOf<Long?>(null) }

    /** 拖拽落定的 (源, 目标)；也是触发「算预览」的开关。 */
    var mergeSource by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    /** 只长按没拖动、或者松在空白处 —— 给一个「合并到…」的列表兜底。 */
    var pickerFor by remember { mutableStateOf<BankWithCount?>(null) }
    var mergePreview by remember { mutableStateOf<QuestionRepository.MergePreview?>(null) }
    var loadingPreview by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var mergeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mergeSource) {
        val pair = mergeSource ?: return@LaunchedEffect
        loadingPreview = true
        mergePreview = null
        // 读两个库的全部题目再两两找重复，几千道题是纯 CPU 活，别放在主线程上
        val loaded = withContext(Dispatchers.Default) {
            runCatching { viewModel.repository.mergePreview(pair.first, pair.second) }.getOrNull()
        }
        loadingPreview = false
        if (loaded == null) {
            mergeSource = null
            mergeMessage = "这两个题库合不了（可能是同一个库，或者已经被删掉了）"
        } else {
            mergePreview = loaded
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题库助手") },
                actions = {
                    IconButton(onClick = onOpenUsage) {
                        Icon(Icons.Default.Info, contentDescription = "用量与费用")
                    }
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "AI 问答")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenImport,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("导入题库") },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { FloatingToggleCard(overlayGranted, floatingRunning, onToggleFloating) }

            if (banks.isEmpty()) {
                item { EmptyState(onOpenImport, onCreateBank = { showCreate = true }) }
            } else {
                if (banks.size >= 2) {
                    item { MergeHint() }
                }
                items(banks, key = { it.bank.id }) { item ->
                    val id = item.bank.id
                    val dragging = dragId == id
                    BankRow(
                        item = item,
                        dragging = dragging,
                        dropTarget = dropId == id && dragId != null && dragId != id,
                        onClick = { onOpenBank(id) },
                        onDelete = { pendingDelete = item },
                        modifier = Modifier
                            // 拎起来的那张要浮在同排其他卡片上面，否则会被后画的盖住
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragDelta else 0f }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragId = id
                                        dragDelta = 0f
                                        // 记下这张卡片在列表视口里的位置，后面把手指的
                                        // 位移加上去就是它在屏幕上的实际位置
                                        dragAnchor = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == id }?.offset ?: 0
                                        dropId = null
                                    },
                                    onDrag = { _, amount ->
                                        dragDelta += amount.y
                                        val y = dragAnchor + dragDelta
                                        dropId = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { info ->
                                                info.key != id &&
                                                    y >= info.offset &&
                                                    y <= info.offset + info.size
                                            }
                                            ?.key as? Long
                                    },
                                    onDragEnd = {
                                        val target = dropId
                                        dragId = null
                                        dragDelta = 0f
                                        dropId = null
                                        if (target != null) {
                                            mergeSource = id to target
                                        } else {
                                            // 没落到任何卡片上：给一个列表让用户点着选
                                            pickerFor = item
                                        }
                                    },
                                    onDragCancel = {
                                        dragId = null
                                        dragDelta = 0f
                                        dropId = null
                                    },
                                )
                            },
                    )
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建题库") },
            text = {
                OutlinedTextField(
                    value = newBankName,
                    onValueChange = { newBankName = it },
                    label = { Text("题库名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newBankName.trim().ifEmpty { "未命名题库" }
                        scope.launch {
                            val id = viewModel.repository.createBank(name)
                            newBankName = ""
                            showCreate = false
                            onCreateBank(id)
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消") }
            },
        )
    }

    val previewNow = mergePreview
    if (previewNow != null) {
        BankMergeDialog(
            preview = previewNow,
            busy = merging,
            onDismiss = {
                if (!merging) {
                    mergePreview = null
                    mergeSource = null
                }
            },
            onConfirm = { keep ->
                merging = true
                val from = previewNow
                scope.launch {
                    val summary = withContext(Dispatchers.Default) {
                        runCatching {
                            viewModel.repository.mergeBanks(from.sourceId, from.targetId, keep)
                        }.getOrNull()
                    }
                    merging = false
                    mergePreview = null
                    mergeSource = null
                    mergeMessage = if (summary == null) {
                        "合并失败，看看日志"
                    } else {
                        "已合并到「" + summary.targetName + "」：搬过去 " + summary.moved +
                            " 道，去重删掉 " + summary.removed + " 道。"
                    }
                }
            },
        )
    }

    if (loadingPreview) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在比对两个题库") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("题目多的时候要等几秒。")
                }
            },
            confirmButton = { },
        )
    }

    // 长按没拖动（或者没拖到卡片上）时的兜底：直接列出来让用户点
    pickerFor?.let { source ->
        val others = banks.filter { it.bank.id != source.bank.id }
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text("把「" + source.bank.name + "」合并到…") },
            text = {
                if (others.isEmpty()) {
                    Text("只有一个题库，没有可以合并的对象。")
                } else {
                    Column {
                        Text(
                            "选一个目标题库，源库的题目会并进去，然后源库被删掉。\n" +
                                "（也可以直接把卡片拖到另一张上面松手。）",
                        )
                        Spacer(Modifier.height(8.dp))
                        others.forEach { other ->
                            TextButton(
                                onClick = {
                                    pickerFor = null
                                    mergeSource = source.bank.id to other.bank.id
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(other.bank.name + "（" + other.questionCount + " 道）")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerFor = null }) { Text("取消") }
            },
        )
    }

    mergeMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { mergeMessage = null },
            title = { Text("合并结果") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { mergeMessage = null }) { Text("好") }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除题库") },
            text = {
                Text("将删除「" + target.bank.name + "」以及其中的 " + target.questionCount + " 道题目，且无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { viewModel.repository.deleteBank(target.bank.id) }
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FloatingToggleCard(
    overlayGranted: Boolean,
    floatingRunning: Boolean,
    onToggleFloating: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("悬浮球拍题", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = when {
                        floatingRunning -> "已开启。切到任意应用点悬浮球即可框选题目。"
                        !overlayGranted -> "需要先授予「显示在其他应用上层」权限。"
                        else -> "开启后可在任意界面截屏拍题、查题库。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = floatingRunning, onCheckedChange = onToggleFloating)
        }
    }
}

/** 一条提示：怎么用拖拽合并。列表里有两个以上题库时才出现。 */
@Composable
private fun MergeHint() {
    Text(
        text = "长按题库卡片，拖到另一张上面松手就能合并；松手时会先显示有多少重复题，由你决定留哪些。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun BankRow(
    item: BankWithCount,
    dragging: Boolean,
    dropTarget: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            // 悬停的那张变色，否则拖拽时没有任何反馈，用户不知道会落到哪儿
            containerColor = if (dropTarget) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (dragging) 12.dp else 1.dp,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.bank.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                val subtitle = buildString {
                    append(item.questionCount)
                    append(" 道题")
                    if (item.bank.subject.isNotBlank()) {
                        append(" · ")
                        append(item.bank.subject)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(onOpenImport: () -> Unit, onCreateBank: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有题库", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "支持导入 .xlsx / .xls / .docx / .csv / .txt。\n" +
                    "Excel 和 Word 会自动识别「题干 / 选项 / 答案 / 解析」这几列。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row {
                TextButton(onClick = onOpenImport) { Text("导入文件") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCreateBank) { Text("手动建库") }
            }
        }
    }
}