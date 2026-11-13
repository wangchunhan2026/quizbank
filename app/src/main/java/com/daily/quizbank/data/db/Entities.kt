package com.daily.quizbank.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 题型常量。用字符串存储而不是 enum，方便后续动态扩展而不必迁移数据库。 */
object QuestionType {
    const val SINGLE = "单选题"
    const val MULTI = "多选题"
    const val JUDGE = "判断题"
    const val FILL = "填空题"
    const val ESSAY = "简答题"
    const val UNKNOWN = "未分类"

    val ALL = listOf(SINGLE, MULTI, JUDGE, FILL, ESSAY, UNKNOWN)

    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return UNKNOWN
        return when {
            t.contains("单选") || t.contains("single") -> SINGLE
            t.contains("多选") || t.contains("multiple") -> MULTI
            t.contains("判断") || t.contains("judge") || t.contains("对错") -> JUDGE
            t.contains("填空") || t.contains("fill") -> FILL
            t.contains("简答") || t.contains("问答") || t.contains("论述") || t.contains("essay") -> ESSAY
            else -> t
        }
    }
}

@Entity(tableName = "banks")
data class BankEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val subject: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = BankEntity::class,
            parentColumns = ["id"],
            childColumns = ["bankId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("bankId")],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bankId: Long,
    val type: String = QuestionType.UNKNOWN,
    val stem: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    val analysis: String = "",
    val tags: String = "",
    val difficulty: Int = 0,
    val source: String = "",
    val starred: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 一次 AI 调用的用量记录。
 *
 * 费用用「微元」（1e-6 元）存成整数，避免浮点累加误差 —— 单次调用只有几厘钱，
 * 用 Double 累加几百次之后数字就开始飘了。
 */
@Entity(tableName = "usage_records")
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val usedImage: Boolean = false,
    val costMicros: Long = 0L,
)

/** 汇总行，直接由 SQL 聚合出来。 */
data class UsageSummary(
    val calls: Int = 0,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val costMicros: Long = 0L,
)

/** 题库 + 题目数量，用于列表页一次查询拿全。 */
data class BankWithCount(
    @Embedded val bank: BankEntity,
    @ColumnInfo(name = "question_count") val questionCount: Int,
)

/** 检索结果 + 相似度得分。得分只在内存中计算，不入库。 */
data class QuestionMatch(
    val question: QuestionEntity,
    val bankName: String,
    val score: Double,
    /**
     * 这次搜进来的那段字，和这条题**解析**的相似度（0 表示没算）。
     *
     * 只在搜题那条路上算，见 [com.daily.quizbank.data.repo.QuestionRepository.match] ——
     * 用户把「题干 + 解析」一整块粘进来搜时，解析也对得上的那条更可能是他要找的。
     * **不是**「相似度」那个 0~1 的展示分，面板上不要把两个数混着说。
     */
    val analysisScore: Double = 0.0,
)

/** 拍题匹配要用的最小题干投影，见 [com.daily.quizbank.util.StemMatcher]。 */
data class StemRow(
    val id: Long,
    val bankId: Long,
    val stem: String,
)
