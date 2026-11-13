package com.daily.quizbank.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daily.quizbank.data.db.QuestionEntity
import com.daily.quizbank.data.db.QuestionType
import com.daily.quizbank.ui.QuizBankViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankDetailScreen(
    viewModel: QuizBankViewModel,
    bankId: Long,
    onBack: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bank by viewModel.repository.bank(bankId).collectAsStateWithLifecycle(initialValue = null)
    var keyword by remember { mutableStateOf("") }
    val questions by viewModel.repository
        .questions(bankId, keyword)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 「只看无解析」：收录时解析是选填的，攒到一定规模就得能把没写解析的挑出来补。
    // 这是个纯本地筛选，不动查询 —— 已经拿到的这一页足够，也省得再加一个 DAO 方法。
    var onlyMissingAnalysis by remember { mutableStateOf(false) }
    val missingAnalysisCount = questions.count { it.analysis.isBlank() }
    val shown = if (onlyMissingAnalysis) questions.filter { it.analysis.isBlank() } else questions

    var editing by remember { mutableStateOf<QuestionEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel.exportMessage) {
        val message = viewModel.exportMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearExportMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(bank?.name ?: "题库", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = questions.size.toString() + " 道题",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 导出成 CSV 走系统分享，对方用本应用或 Excel 都能打开。
                    // 列名就是导入器认得的那几个，所以导出的文件能被原样导回来。
                    IconButton(onClick = { viewModel.exportBank(bankId, bank?.name.orEmpty()) }) {
                        Icon(Icons.Default.Share, contentDescription = "导出")
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Default.Add, contentDescription = "导入")
                    }
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "新建题目")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text("搜索题干、答案、知识点") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = onlyMissingAnalysis,
                    onClick = { onlyMissingAnalysis = !onlyMissingAnalysis },
                    label = { Text("只看无解析（" + missingAnalysisCount + "）") },
                )
            }

            if (shown.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            onlyMissingAnalysis -> "这个题库里的题都写了解析"
                            keyword.isBlank() -> "这个题库还是空的，点右上角「+」导入或新建"
                            else -> "没有匹配的题目"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { question ->
                        QuestionCard(
                            question = question,
                            onToggleStar = {
                                scope.launch { viewModel.repository.setStarred(question.id, !question.starred) }
                            },
                            onEdit = { editing = question },
                            onDelete = {
                                scope.launch { viewModel.repository.deleteQuestion(question) }
                            },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        QuestionEditorDialog(
            initial = QuestionEntity(bankId = bankId, stem = ""),
            title = "新建题目",
            onDismiss = { creating = false },
            onSave = { draft ->
                scope.launch { viewModel.repository.saveQuestion(draft) }
                creating = false
            },
        )
    }

    editing?.let { target ->
        QuestionEditorDialog(
            initial = target,
            title = "编辑题目",
            onDismiss = { editing = null },
            onSave = { draft ->
                scope.launch { viewModel.repository.saveQuestion(draft) }
                editing = null
            },
        )
    }
}

@Composable
private fun QuestionCard(
    question: QuestionEntity,
    onToggleStar: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(question.type)
                Spacer(Modifier.width(8.dp))
                if (question.tags.isNotBlank()) {
                    Text(
                        text = question.tags,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = onToggleStar, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (question.starred) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "收藏",
                        modifier = Modifier.size(18.dp),
                        tint = if (question.starred) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(question.stem, style = MaterialTheme.typography.bodyLarge)

            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                question.options.forEach { option ->
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    if (question.answer.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        LabeledText("答案", question.answer, MaterialTheme.colorScheme.tertiary)
                    }
                    if (question.analysis.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        LabeledText("解析", question.analysis, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("编辑")
                        }
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledText(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun TypeBadge(type: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = type,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionEditorDialog(
    initial: QuestionEntity,
    title: String,
    onDismiss: () -> Unit,
    onSave: (QuestionEntity) -> Unit,
) {
    var stem by remember { mutableStateOf(initial.stem) }
    var options by remember { mutableStateOf(initial.options.joinToString("\n")) }
    var answer by remember { mutableStateOf(initial.answer) }
    var analysis by remember { mutableStateOf(initial.analysis) }
    var tags by remember { mutableStateOf(initial.tags) }
    var type by remember { mutableStateOf(initial.type) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = { Text("题干") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = options,
                    onValueChange = { options = it },
                    label = { Text("选项（每行一个）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QuestionType.ALL.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(candidate) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("答案") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = analysis,
                    onValueChange = { analysis = it },
                    label = { Text("解析") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("知识点 / 标签") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            stem = stem.trim(),
                            options = options.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                            answer = answer.trim(),
                            analysis = analysis.trim(),
                            tags = tags.trim(),
                            type = type,
                        ),
                    )
                },
                enabled = stem.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
