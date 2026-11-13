package com.daily.quizbank.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daily.quizbank.data.db.QuestionEntity
import com.daily.quizbank.data.repo.QuestionRepository

/**
 * 合并题库之前给用户看的确认页。
 *
 * 为什么不是一个简单的「确定要合并吗」：两个库里重复的题**答案未必一样**。
 * 真实题库里就有同一道题出现两次、四个选项一字不差、答案一个标 C 一个标 B 的情况
 * （导入那块早就因为同一件事加了重复确认页）。合并要是随手挑一条留下，就是无声地丢掉另一个答案。
 * 所以这里逐组摆出来让用户自己挑，默认选目标库那条。
 *
 * 重复分两档，界面上要区分开：**一模一样**（归一化后完全相同）和**疑似同一道**（相似度 ≥ 0.88）。
 * 后者是算出来的，会错，所以标注出来让用户多看一眼。
 */
@Composable
fun BankMergeDialog(
    preview: QuestionRepository.MergePreview,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    // 组下标 -> 选中要留下的那条 id
    //
    // 用**下标**当键，不用 group.key：key 是「分桶键」，同一个桶里可能生出好几组
    // （开头八个字一样的近似题），拿它当 LazyColumn 的 key 会直接崩 ——
    // 用户 2026-10-09 拖题库合并时就是这么闪退的：
    //   IllegalArgumentException: Key "男70岁直肠指诊" was already used.
    // 顺手也修了「两组共用一个 key，选一组把另一组的选择也改了」。
    val choices = remember(preview) {
        mutableStateMapOf<Int, Long>().apply {
            preview.groups.forEachIndexed { index, group ->
                put(index, group.preferred(preview.targetId).id)
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "合并题库",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "「" + preview.sourceName + "」→「" + preview.targetName + "」\n" +
                        "源库 " + preview.sourceCount + " 道，目标库 " + preview.targetCount + " 道，" +
                        "其中 " + preview.groups.size + " 组疑似重复（共 " + preview.duplicateCount + " 道）。\n" +
                        "合并后源库「" + preview.sourceName + "」会被删掉，题目并进目标库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (preview.groups.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                preview.groups.forEachIndexed { index, group ->
                                    choices[index] = group.preferred(preview.targetId).id
                                }
                            },
                        ) { Text("默认选择") }
                        TextButton(
                            onClick = {
                                preview.groups.forEachIndexed { index, group ->
                                    val mine = group.items.firstOrNull { it.bankId == preview.sourceId }
                                    if (mine != null) choices[index] = mine.id
                                }
                            },
                        ) { Text("都留源库的") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "下面是每一组重复题，点一下选「留下哪一条」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (preview.groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "没有重复题，直接合并就行。",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // key 用下标：group.key 不是唯一标识（见上面 choices 的注释）
                            itemsIndexed(preview.groups, key = { index, _ -> index }) { index, group ->
                                MergeGroupCard(
                                    group = group,
                                    sourceId = preview.sourceId,
                                    sourceName = preview.sourceName,
                                    targetName = preview.targetName,
                                    selectedId = choices[index],
                                    onSelect = { choices[index] = it },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val keep = HashSet<Long>()
                            choices.values.forEach { keep.add(it) }
                            onConfirm(keep)
                        },
                        enabled = !busy,
                    ) { Text(if (busy) "正在合并…" else "合并") }
                }
            }
        }
    }
}

@Composable
private fun MergeGroupCard(
    group: QuestionRepository.MergeGroup,
    sourceId: Long,
    sourceName: String,
    targetName: String,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
) {
    val exact = group.key == group.items.firstOrNull()?.let { com.daily.quizbank.util.Similarity.normalize(it.stem) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                if (exact) "一模一样（归一化后完全相同）" else "疑似同一道（相似度 ≥ 88%）",
                style = MaterialTheme.typography.labelMedium,
                color = if (exact) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            group.items.forEach { question ->
                QuestionChoice(
                    question = question,
                    bankLabel = if (question.bankId == sourceId) sourceName else targetName,
                    fromSource = question.bankId == sourceId,
                    selected = question.id == selectedId,
                    onSelect = { onSelect(question.id) },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun QuestionChoice(
    question: QuestionEntity,
    bankLabel: String,
    fromSource: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = question.stem.replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(if (fromSource) "源库" else "目标库")
                    append(" · ")
                    append(bankLabel)
                    append(" · 答案 ")
                    append(question.answer.ifBlank { "（空）" })
                    append(" · 选项 ")
                    append(question.options.size)
                    append(" 个")
                    if (question.analysis.isNotBlank()) append(" · 有解析")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
