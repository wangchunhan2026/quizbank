package com.daily.quizbank.ui.screen

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daily.quizbank.data.db.QuestionEntity
import com.daily.quizbank.data.repo.QuestionRepository
import com.daily.quizbank.ui.theme.WarningAmber

/**
 * 「整理题库 → 找出相似题」的结果页：每一组像到该留一条的题，让用户自己挑留哪条，或者都留。
 *
 * 三条设计上的取舍：
 *
 *  1. **相似是程序算出来的**（[QuestionRepository.findSimilarQuestions]，复用 Similarity 和分桶），
 *     不是问 AI 得来的。AI 读两条题干只能给一段「看着挺有道理」的话，分不出哪两条真的像；
 *     而这件事本来就是一个字符串相似度问题，算出来的还有分数可以摆给用户看。
 *  2. **删不删永远是用户点的**。这里只把「程序认为像」的东西摊开，默认选中信息最全的那条，
 *     但一条都不会自己删 —— 题库里近似题常常一个答案对、一个答案错（OCR 认错），
 *     挑错了就是无声地丢掉一个正确答案。
 *  3. **列表 key 一律用下标**，不用 group.key：那是分桶键，同一个桶里会生出好几组，
 *     拿它当 key 会直接崩（2026-10-09 合并题库时就是这么闪退的，见 BankMergeDialog）。
 */
@Composable
fun SimilarQuestionsDialog(
    report: QuestionRepository.SimilarReport,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    // 组下标 -> 用户选的那条 id。**键不在 = 这一组「都留」**，所以初值全都放进去。
    val choices = remember(report) {
        mutableStateMapOf<Int, Long>().apply {
            report.groups.forEachIndexed { index, group -> put(index, group.preferred().id) }
        }
    }

    val deleteCount = report.groups.indices.sumOf { index ->
        val keep = choices[index] ?: return@sumOf 0
        report.groups[index].items.count { it.id != keep }
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
                Text("题库里的相似题", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "扫了 " + report.scanned + " 道题，找出 " + report.groups.size + " 组像到该留一条的" +
                        (if (report.truncated) "（太多了，先列这些）" else "") + "。\n" +
                        "下面是程序算出来的相似题：一模一样的那种（归一化后完全相同）一定重复，" +
                        "标着「疑似」的只是像，可能只是同一道题的两个版本；" +
                        "标着「一条是另一条的一部分」的是**同一条题上次没收完**留下的半截" +
                        "（材料只拍了一半、或者小问没填完就点了确定），留长的那条就行。\n" +
                        "每条都默认选了信息最全的那条，点一下可以改成留别的，或者选「都留」。" +
                        "删掉的题找不回来 —— 想留个后路，先去题库页导出一次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (report.groups.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("没有发现相似题。", style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // key 用下标：group.key 是分桶键、不是唯一标识（见类注释第 3 条）
                            itemsIndexed(report.groups, key = { index, _ -> index }) { index, group ->
                                SimilarGroupCard(
                                    group = group,
                                    selectedId = choices[index],
                                    onSelect = { choices[index] = it },
                                    onKeepAll = { choices.remove(index) },
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
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("先不动") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            // 只把「用户没选它、但它在这一组里」的 id 交出去 —— 仓库照着删，
                            // 不会再自己算一遍「谁该删」（见 deleteQuestionsByIds 的说明）
                            val deleteIds = ArrayList<Long>()
                            report.groups.forEachIndexed { index, group ->
                                val keep = choices[index] ?: return@forEachIndexed
                                group.items.forEach { if (it.id != keep) deleteIds.add(it.id) }
                            }
                            onConfirm(deleteIds)
                        },
                        enabled = !busy && deleteCount > 0,
                    ) {
                        Text(
                            when {
                                busy -> "正在清理…"
                                deleteCount == 0 -> "都留，没什么可清理的"
                                else -> "清理 " + deleteCount + " 条"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarGroupCard(
    group: QuestionRepository.SimilarGroup,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onKeepAll: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                when {
                    group.exact -> "一模一样（归一化后完全相同）"
                    group.partial -> "一条是另一条的一部分（有一条像是没收完的那半截）"
                    else -> "疑似同一道（相似度 ≥ 88%）"
                },
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    group.exact -> MaterialTheme.colorScheme.primary
                    group.partial -> WarningAmber
                    else -> MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            group.items.forEach { question ->
                SimilarChoice(
                    question = question,
                    bankName = group.bankName,
                    selected = question.id == selectedId,
                    onSelect = { onSelect(question.id) },
                )
                Spacer(Modifier.height(4.dp))
            }
            // 「都留」也是这一组的一个选项：有些近似题答案不一样，两条都得留着
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .selectable(selected = selectedId == null, onClick = onKeepAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedId == null, onClick = onKeepAll)
                Spacer(Modifier.width(4.dp))
                Text("都留（这一组先不动）", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SimilarChoice(
    question: QuestionEntity,
    bankName: String,
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
                    append(bankName)
                    append(" · 答案 ")
                    append(question.answer.ifBlank { "（空）" })
                    append(" · 选项 ")
                    append(question.options.size)
                    append(" 个")
                    if (question.analysis.isNotBlank()) append(" · 有解析")
                    append(" · 题干 ")
                    append(question.stem.length)
                    append(" 字")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
