package com.daily.quizbank.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daily.quizbank.data.db.UsageRecordEntity
import com.daily.quizbank.data.db.UsageSummary
import com.daily.quizbank.ui.QuizBankViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 用量与费用。
 *
 * token 数字来自 API 返回的 usage 字段，是**真实值不是估算**；
 * 只有金额是换算出来的（官方分高峰/空闲时段计价，这里统一按高峰价算，属于上限）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    viewModel: QuizBankViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val summary by viewModel.repository.usageSummary
        .collectAsStateWithLifecycle(initialValue = UsageSummary())
    val records by viewModel.repository.usageRecords()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用量与费用") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { viewModel.repository.clearUsage() } }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空记录")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("累计消耗", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))

                        Text(
                            "¥ " + yuan(summary.costMicros),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "按高峰时段单价估算的金额上限，实际账单通常更低",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(14.dp))
                        StatRow("调用次数", summary.calls.toString() + " 次")
                        StatRow("输入 tokens", format(summary.promptTokens))
                        StatRow("输出 tokens", format(summary.completionTokens))
                        StatRow(
                            "合计 tokens",
                            format(summary.promptTokens + summary.completionTokens),
                        )
                    }
                }
            }

            if (records.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text("还没有记录", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "只有「未命中题库、交给 AI 作答」的题才会产生消耗。" +
                                    "命中题库的题直接给标准答案，不花一分钱。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item { Text("明细", style = MaterialTheme.typography.titleMedium) }
                items(records, key = { it.id }) { record -> UsageRow(record) }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UsageRow(record: UsageRecordEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.model + if (record.usedImage) " · 含图片" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    TIME_FORMAT.format(Date(record.timestamp)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "输入 " + format(record.promptTokens.toLong()) +
                        " / 输出 " + format(record.completionTokens.toLong()) +
                        if (record.cachedTokens > 0) "（缓存命中 " + record.cachedTokens + "）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "¥" + yuan(record.costMicros),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

/** token 用千分位显示，动辄上万位读起来太吃力。 */
private fun format(value: Long): String = String.format(Locale.US, "%,d", value)

/** 微元换算成「元」。 */
private fun yuan(micros: Long): String = String.format(Locale.US, "%.4f", micros / 1_000_000.0)
