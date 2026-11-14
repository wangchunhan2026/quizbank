package com.daily.quizbank.data.repo

import com.daily.quizbank.data.db.AppDatabase
import com.daily.quizbank.data.db.BankEntity
import com.daily.quizbank.data.db.BankWithCount
import com.daily.quizbank.data.db.QuestionEntity
import com.daily.quizbank.data.db.QuestionType
import com.daily.quizbank.data.ai.Pricing
import com.daily.quizbank.data.db.QuestionMatch
import com.daily.quizbank.data.db.StemRow
import com.daily.quizbank.data.db.UsageRecordEntity
import com.daily.quizbank.data.db.UsageSummary
import com.daily.quizbank.data.import.ImportSummary
import com.daily.quizbank.data.import.QuestionDraft
import com.daily.quizbank.data.import.QuestionParser
import com.daily.quizbank.util.AnalysisRank
import com.daily.quizbank.util.DedupPolicy
import com.daily.quizbank.util.FragmentRepair
import com.daily.quizbank.util.OcrText
import com.daily.quizbank.util.Similarity
import com.daily.quizbank.util.StemEntry
import com.daily.quizbank.util.StemHit
import com.daily.quizbank.util.StemMatcher
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 一道被判为重复的草稿，以及它「和谁」重复。
 *
 * 光说「有 500 道重复」用户是没法判断的：真正要比的是**两边的答案**——
 * 真实文件里就有同一道题出现两次、四个选项一字不差、答案一个标 C 一个标 B 的情况，
 * 到底留哪个只能人来定。
 *
 * [exact] 把「一模一样」和「只是很像」分成两档（判据见 [DedupPolicy]）：近似的**未必是同一道题**，
 * 界面上必须标出来，程序也不能替他默认丢掉任何一条。
 */
data class DuplicateDraft(
    /** 在待入库 drafts 里的下标。 */
    val index: Int,
    /** 题库里那条的 id；对照项不是题库条目时为 -1。 */
    val existingId: Long = -1L,
    /** 题库里那条在哪个题库；-1 = 没有。 */
    val existingBankId: Long = -1L,
    /** 那条在**别的**题库里时那个题库的名字；就在目标题库里、或者没有对照项时为 null。 */
    val existingBankName: String? = null,
    /** 与题库里已有的题重复时，那条题的原文；文件内部重复时为 null。 */
    val existingStem: String?,
    val existingAnswer: String?,
    val existingOptions: List<String>,
    /** 顶掉旧题之前要摆给用户看的几栏（见 [DedupPolicy.extraFields]）。 */
    val existingAnalysis: String = "",
    val existingTags: String = "",
    val existingStarred: Boolean = false,
    /** 与文件里更早出现的那条重复时，那条的下标；否则 -1。 */
    val earlierIndex: Int,
    /** 一模一样时为 true；第二档（算出来的近似）为 false。 */
    val exact: Boolean = true,
    /** 第二档算出来的相似度，展示用；一模一样时为 1.0。 */
    val score: Double = 1.0,
) {
    /** true = 只是很像，不保证是同一道题。 */
    val near: Boolean get() = !exact

    /** true = 对照项是**这个**题库里已经有的一条题（选了「留新的」会被顶掉）。 */
    val inBank: Boolean get() = existingId > 0L && existingBankName == null

    /** true = 对照项在别的题库里 —— 这次导入动不了它，只能用「这条不入库」避开重复。 */
    val otherBank: Boolean get() = existingBankName != null

    /** 交给 [DedupPolicy] 的对照项：那边的判断不碰数据库，也就能进单元测试。 */
    val counterpart: DedupPolicy.Counterpart
        get() = DedupPolicy.Counterpart(
            id = existingId,
            inBank = inBank,
            otherBankName = existingBankName,
            stem = existingStem.orEmpty(),
            answer = existingAnswer.orEmpty(),
            options = existingOptions,
            analysis = existingAnalysis,
            tags = existingTags,
            starred = existingStarred,
        )
}

/**
 * 拍题匹配的结果。
 *
 * 除了展示用的命中列表，还要带上 [margin] 和 [runnerUp]，理由见
 * [com.daily.quizbank.util.MatchOutcome] —— 「第二道不同的题差多少分」
 * 决定了这次命中能不能直接采信。
 */
data class BankMatchOutcome(
    val matches: List<QuestionMatch>,
    val margin: Double,
    val runnerUp: QuestionMatch?,
    /** 不能直接采信时，一句给人看的理由；可以采信时为 null。 */
    val hesitation: String?,
    /**
     * 因为「只撞上一句通用问法」被挡掉的候选有几条（见 StemMatcher.looksGenericOnly）。
     *
     * 只写进日志：面板上不摆它们，用户看不到；但排查「明明看到一条相近的题、
     * 面板却说题库里没有」时，日志里得有这一句。
     */
    val genericOnly: Int = 0,
) {
    val best: QuestionMatch? get() = matches.firstOrNull()

    /** 分数够高、确认是同一个问法，可以直接用题库答案。 */
    val confident: Boolean
        get() {
            val top = matches.firstOrNull() ?: return false
            return top.score >= Similarity.MATCH_THRESHOLD &&
                hesitation == null &&
                top.question.answer.isNotBlank()
        }

    /** 分数够高但不敢采信：题库里有近似题，或者问法被改过。 */
    val ambiguous: Boolean
        get() {
            val top = matches.firstOrNull() ?: return false
            return top.score >= Similarity.MATCH_THRESHOLD && hesitation != null
        }

    companion object {
        val EMPTY = BankMatchOutcome(emptyList(), 0.0, null, null)
    }
}

class QuestionRepository(private val db: AppDatabase) {

    val banks: Flow<List<BankWithCount>> = db.bankDao().observeAllWithCount()

    fun bank(id: Long): Flow<BankEntity?> = db.bankDao().observeById(id)

    fun questions(bankId: Long, keyword: String): Flow<List<QuestionEntity>> =
        if (keyword.isBlank()) {
            db.questionDao().observeByBank(bankId)
        } else {
            db.questionDao().searchInBank(bankId, keyword)
        }

    // ------------------------------------------------------------------ 题库

    suspend fun createBank(name: String, subject: String = "", note: String = ""): Long {
        val trimmed = bankNameOf(name)
        // 重名题库直接复用，避免用户连点两次「新建」得到两个空壳
        db.bankDao().byName(trimmed)?.let { return it.id }
        return db.bankDao().insert(BankEntity(name = trimmed, subject = subject.trim(), note = note.trim()))
    }

    /**
     * 按名字查题库 id；没有这个库就返回 null。
     *
     * 导入那条路靠它把「建库」推迟到**真正入库那一刻**：以前是用户一点「导入」就先把库建出来，
     * 于是他看完重复题点「返回预览」、或者干脆退出去，库里就留下一个空题库（2026-10-13 补的缺口）。
     * 名字规范化和 [createBank] 必须走同一个 [bankNameOf]，否则会出现「查不到 → 又建一个同名库」。
     */
    suspend fun bankIdByName(name: String): Long? = db.bankDao().byName(bankNameOf(name))?.id

    /** 题库名规范化：去空白，空白名给个兜底。查库和建库必须用同一个，否则会建出两个同名的。 */
    private fun bankNameOf(name: String): String = name.trim().ifEmpty { "未命名题库" }

    suspend fun updateBank(bank: BankEntity) {
        db.bankDao().update(bank.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteBank(id: Long) {
        db.bankDao().deleteById(id)
        invalidateMatcher()
    }

    suspend fun totalQuestions(): Int = db.questionDao().totalCount()

    // ------------------------------------------------------------------ 题目

    suspend fun saveQuestion(question: QuestionEntity): Long =
        if (question.id == 0L) {
            db.questionDao().insert(question).also { db.bankDao().touch(question.bankId) }
        } else {
            db.questionDao().update(question)
            db.bankDao().touch(question.bankId)
            question.id
        }.also { invalidateMatcher() }

    suspend fun deleteQuestion(question: QuestionEntity) {
        db.questionDao().delete(question)
        db.bankDao().touch(question.bankId)
        invalidateMatcher()
    }

    /**
     * 按 id 删一条题。
     *
     * 收录面板「用这次这条顶掉那条半截旧条目」走的就是这里 —— 它手上只有 id，
     * 而且删完必须让匹配索引失效，否则刚删的那条还会继续参与匹配。
     */
    suspend fun deleteQuestionById(id: Long) {
        val row = db.questionDao().byIds(listOf(id)).firstOrNull() ?: return
        deleteQuestion(row)
    }

    suspend fun setStarred(id: Long, starred: Boolean) = db.questionDao().setStarred(id, starred)

    /**
     * 发现出来的「一条草稿 + 它的对照项」，最后再补上展示用的全文。
     *
     * 扫描阶段只用 [StemRow]（id / bankId / 题干三列）：几千道题的选项和解析加起来好几个 MB，
     * 判重不看它们，真正要摆到界面上的那几条最后按 id 取回来。
     */
    private class DupHit(
        val index: Int,
        val row: StemRow?,
        val earlierIndex: Int,
        val exact: Boolean,
        val score: Double,
    )

    /**
     * 批量为一道草稿找出「它和谁重复了」。
     *
     * 判重分两档，和「合并题库」「找相似题」用的是**同一套判据**（见 [DedupPolicy]）：
     *
     *  1. **一模一样**：归一化题干（去空白 + 转小写，**保留标点**，[dedupKey]）完全相同。
     *     这一档和入库时用的是同一个 [dedupKey]，否则预览页说重复、入库时又不重复，
     *     就会出现「提示跳过 500 道，结果只少了 3 道」这种自相矛盾；
     *  2. **近似**：去掉题号取前 8 个字分桶，同桶内 [Similarity.score] ≥
     *     [DedupPolicy.NEAR_DUPLICATE]。这一档是 2026-10-13 补的缺口：他导出题库、
     *     改一改再导回来时，OCR 差一个字的同一条题在第一档里对不上，于是**静默进了两份**，
     *     拍题时反倒因为「库里有两道几乎一样的题」退回 AI（真机上屏幕上那一条对
     *     「某短条目被认错一个字」就是这么来的）。
     *
     * 对照项优先级：**目标题库里那条** > **别的题库里那条** > **同一份文件里更早那条**。
     * 跨库那条也要报（2026-10-13 补）：导进另一个库之前用户得知道这件事；
     * 但这次导入**动不了**别的题库，界面上的「留新的」不会去删它。
     *
     * @param bankId 目标题库；<= 0 表示要新建一个空题库（此时只查别的题库和文件内部）。
     */
    suspend fun findDuplicates(bankId: Long, drafts: List<QuestionDraft>): List<DuplicateDraft> {
        if (drafts.isEmpty()) return emptyList()
        val dao = db.questionDao()
        val rows = dao.stems()
        val bankNames = db.bankDao().all().associate { it.id to it.name }

        val targetByKey = HashMap<String, StemRow>()
        val otherByKey = HashMap<String, StemRow>()
        for (row in rows) {
            val key = dedupKey(row.stem)
            if (key.isEmpty()) continue
            if (row.bankId == bankId) {
                targetByKey.putIfAbsent(key, row)
            } else {
                otherByKey.putIfAbsent(key, row)
            }
        }

        // 近似那一档要分桶：目标库、别的库、以及同一份文件里更早的草稿放在一起比。
        val buckets = HashMap<String, MutableList<StemRow>>()
        for (row in rows) {
            val key = DedupPolicy.bucketKey(row.stem)
            if (!DedupPolicy.bucketUsable(key)) continue
            buckets.getOrPut(key) { ArrayList() }.add(row)
        }

        val found = ArrayList<DupHit>()
        val decided = HashSet<Int>()
        val seenExact = HashMap<String, Int>()
        val seenBuckets = HashMap<String, MutableList<Int>>()

        // 第一档：一模一样
        drafts.forEachIndexed { index, draft ->
            val key = dedupKey(draft.stem)
            if (key.isEmpty()) return@forEachIndexed
            val target = targetByKey[key]
            val other = if (target == null) otherByKey[key] else null
            val earlier = seenExact[key]
            if (target != null || other != null || earlier != null) {
                found.add(DupHit(index, target ?: other, earlier ?: -1, exact = true, score = 1.0))
                decided.add(index)
            }
            if (earlier == null) seenExact[key] = index
        }

        // 第二档：近似。一道题只问一次 —— 已经在第一档里有结论的就不再进这一档，
        // 否则同一道题会以「和库里那条一样」和「和文件里那条很像」两个身份问两遍。
        drafts.forEachIndexed { index, draft ->
            val bucket = DedupPolicy.bucketKey(draft.stem)
            if (!DedupPolicy.bucketUsable(bucket)) return@forEachIndexed
            if (index !in decided) {
                var bestRow: StemRow? = null
                var bestInTarget = false
                var bestScore = 0.0
                for (row in buckets[bucket].orEmpty()) {
                    val score = Similarity.score(row.stem, draft.stem)
                    if (!DedupPolicy.isNear(score)) continue
                    val inTarget = row.bankId == bankId
                    // 目标题库里那条优先；同类之间取更像的
                    if (bestRow == null || (inTarget && !bestInTarget) ||
                        (inTarget == bestInTarget && score > bestScore)
                    ) {
                        bestRow = row
                        bestInTarget = inTarget
                        bestScore = score
                    }
                }
                var bestEarlier = -1
                if (bestRow == null) {
                    for (earlier in seenBuckets[bucket].orEmpty()) {
                        val score = Similarity.score(drafts[earlier].stem, draft.stem)
                        if (DedupPolicy.isNear(score)) {
                            bestEarlier = earlier
                            bestScore = score
                            break
                        }
                    }
                }
                if (bestRow != null || bestEarlier >= 0) {
                    found.add(DupHit(index, bestRow, bestEarlier, exact = false, score = bestScore))
                    decided.add(index)
                }
            }
            seenBuckets.getOrPut(bucket) { ArrayList() }.add(index)
        }

        if (found.isEmpty()) return emptyList()

        val ids = found.mapNotNull { it.row?.id }.distinct()
        val full = ids.chunked(BATCH_LIMIT).flatMap { dao.byIds(it) }.associateBy { it.id }

        return found.map { hit ->
            val row = hit.row?.let { full[it.id] }
            DuplicateDraft(
                index = hit.index,
                existingId = row?.id ?: -1L,
                existingBankId = row?.bankId ?: -1L,
                existingBankName = row?.let {
                    if (it.bankId == bankId) null else bankNames[it.bankId] ?: "未命名题库"
                },
                existingStem = row?.stem,
                existingAnswer = row?.answer,
                existingOptions = row?.options.orEmpty(),
                existingAnalysis = row?.analysis.orEmpty(),
                existingTags = row?.tags.orEmpty(),
                existingStarred = row?.starred ?: false,
                earlierIndex = hit.earlierIndex,
                exact = hit.exact,
                score = hit.score,
            )
        }.sortedBy { it.index }
    }

    /**
     * 批量入库，按「归一化题干」去重。
     *
     * 用归一化后的精确相等判重而不是相似度：相似度是 O(n²)，
     * 几千道题的题库会卡到不可用；而重复导入同一份文件正是精确重复这个最常见的情况。
     *
     * @param skipDuplicates false 表示「用户选了全部导入」——
     *   这时重复题也要入库。题库里因此会出现同一道题的两份拷贝，
     *   而两份的答案可能不同（真实文件里就有这种），匹配时会取先入库的那条。
     * @param resolvedDuplicates 由用户逐题核对后决定丢弃的条数，只用于统计口径。
     * @param replaceIds 用户核对重复题时选了「保留新导入的这条」的那些**库里旧题**，
     *   入库前先顶掉。不顶掉的话他这一选等于没选：库里两条都在，而拍题匹配取的是
     *   先入库的那条（id 小的优先），答题时用的反而是他刚否掉的那一份。
     * @param keptBoth 近似重复里选了「两条都留」的条数，只用于统计口径。
     */
    suspend fun importDrafts(
        bankId: Long,
        drafts: List<QuestionDraft>,
        skipDuplicates: Boolean = true,
        resolvedDuplicates: Int = 0,
        replaceIds: List<Long> = emptyList(),
        keptBoth: Int = 0,
    ): ImportSummary {
        val dao = db.questionDao()

        // 先顶掉旧题，再把这一刻的库状态当判重依据 —— 顺序反了的话，被顶掉的那几条
        // 还会以「库里已经有了」的身份把新导入的这条挡在门外。
        val replaced = if (replaceIds.isEmpty()) {
            emptyList()
        } else {
            val wanted = replaceIds.filter { it > 0L }.distinct().toSet()
            // 只删**就在这个题库里**的题：id 由界面传回来，多一道保险不会误删别的库
            val owned = dao.byBank(bankId).filter { it.id in wanted }
            owned.map { it.id }.chunked(BATCH_LIMIT).forEach { dao.deleteByIds(it) }
            if (owned.isNotEmpty()) {
                invalidateMatcher()
                Log.i(TAG, "导入顶掉旧题 " + owned.size + " 道：" + owned.first().stem.take(30))
            }
            owned
        }

        val existing = if (skipDuplicates) {
            dao.byBank(bankId).mapTo(HashSet()) { dedupKey(it.stem) }
        } else {
            HashSet()
        }

        val pending = ArrayList<QuestionEntity>()
        val duplicateSamples = ArrayList<String>()
        var duplicates = 0
        var blank = 0
        val now = System.currentTimeMillis()

        for (draft in drafts) {
            if (draft.stem.isBlank()) {
                blank++
                continue
            }
            if (skipDuplicates) {
                val key = dedupKey(draft.stem)
                if (key.isEmpty() || !existing.add(key)) {
                    duplicates++
                    if (duplicateSamples.size < DUPLICATE_SAMPLE_LIMIT) {
                        duplicateSamples.add(draft.stem)
                    }
                    continue
                }
            }
            pending.add(
                QuestionEntity(
                    bankId = bankId,
                    type = draft.type,
                    stem = draft.stem,
                    options = draft.options,
                    answer = draft.answer,
                    analysis = draft.analysis,
                    tags = draft.tags,
                    difficulty = draft.difficulty,
                    source = "导入",
                    createdAt = now,
                ),
            )
        }

        // 入库拿到的新 id 要留一份：收录那条路靠它把「刚收的这几条」撤回来
        // （用户点完确定才发现库里早就有一条更全的，见 CollectResult.savedIds）
        var insertedIds: List<Long> = emptyList()
        if (pending.isNotEmpty()) {
            insertedIds = dao.insertAll(pending)
            db.bankDao().touch(bankId)
            invalidateMatcher()
        }
        return ImportSummary(
            inserted = pending.size,
            duplicates = duplicates + resolvedDuplicates,
            blank = blank,
            duplicateSamples = duplicateSamples,
            replaced = replaced.size,
            replacedSamples = replaced.map { it.stem },
            keptBoth = keptBoth,
            insertedIds = insertedIds,
        )
    }

    /**
     * 去重用的归一化：只统一空白和大小写，**保留标点**。
     *
     * 这里绝对不能复用 [Similarity.normalize] —— 它会把所有标点都删掉，
     * 于是「以下哪个正确？」和「以下哪个正确。」会被判成同一道题而被静默丢弃。
     * 那个函数是为「一方带噪声的相似度匹配」设计的，拿来做精确去重是错的。
     */
    private fun dedupKey(stem: String): String = stem.replace(WHITESPACE, "").lowercase()

    // ------------------------------------------------------------------ 检索与匹配

    /**
     * 拍题匹配。
     *
     * 走内存里的 [StemMatcher] 索引，而不是「SQL LIKE 探针 + 逐条算相似度」。
     * 换掉 SQL 预筛有两个实打实的理由：
     *
     * 1. LIKE 是拿「已归一化的探针」去比「没归一化的题干」。题库题干里凡是带括号、
     *    顿号的地方（「某规程适用于（）……」），跨标点的探针一律匹配不上，
     *    白丢候选。索引里两边都归一化过，没有这个问题。
     * 2. 探针原本只取 OCR 文本开头 32 个字，而全屏截图的开头全是状态栏和选项，
     *    预筛必然落空，只剩「全表扫描」兜底；题库一旦超过 20000 题，兜底也会被关掉。
     *
     * 索引在第一次匹配时构建（只查 id/bankId/stem 三列），题库有任何写入就失效，
     * 下次匹配重建。几千道题重建一次是几毫秒的量级。
     *
     * @param withAnalysis 让**解析**也参与排序（2026-10-12 用户要的）。**只在搜题那条路上开**：
     *   用户搜题时常常把「题干 + 解析」一整块粘进来，解析也对得上的那条更可能是他要找的；
     *   拍题不开 —— 截屏那条路上「解析」本来就不该参与「像不像」的判断。
     *   排序规矩在 [AnalysisRank] 里，采信与否不受它影响。
     */
    suspend fun match(
        stem: String,
        // 面板上最多摆 3 条（用户 2026-10-09 的要求）：再多他也挑不过来，每条候选还占一大块高度
        limit: Int = 3,
        threshold: Double = Similarity.DEFAULT_THRESHOLD,
        withAnalysis: Boolean = false,
    ): BankMatchOutcome {
        // 要按解析重排就得多捞几条再排：只捞 limit 条的话，排完还是这几条，
        // 排在后面那条「解析也对上」的根本没有机会露头。
        val outcome = matcher().match(
            stem,
            limit = if (withAnalysis) ANALYSIS_POOL else limit,
            threshold = threshold,
        )
        if (outcome.hits.isEmpty()) {
            // 一条都没到展示线时，把「真正最像的那几条」和索引规模都写进日志。
            //
            // 2026-10-12 用户报「拍题显示没收录、收录时又说库里有高度相似的」——
            // 那天日志里只有「命中 0 条」，既看不出是「真的一条都不像」还是「差一点」，
            // 也看不出索引是不是少了几条。所以这一行必须打（**注意它得在早返回之前**：
            // 第一次加的时候写在函数末尾，结果永远走不到，白加了）。
            val near = outcome.probe.joinToString(" ; ") {
                (it.score * 100).toInt().toString() + "% " + it.entry.stem.replace("\n", " ").take(28)
            }
            // 库里到底有没有一条和这次输入（题干部分）**完全对得上**的题？
            // 有 → 是算法/索引漏了；没有 → 这道题确实不在库里，那句「高度相似」说的是别的题。
            // 这一问是整条诊断里最值钱的：它把「谁错了」直接分开。
            val key = StemMatcher.stemOnlyKey(stem)
            val exact = withContext(Dispatchers.Default) {
                db.questionDao().stems().count { Similarity.normalize(it.stem) == key }
            }
            val reason = if (outcome.genericOnly > 0) {
                "被「通用问法」判据挡掉 " + outcome.genericOnly + " 条"
            } else {
                "一条都没到展示线(" + Similarity.DEFAULT_THRESHOLD + ")"
            }
            Log.i(
                TAG,
                "匹配：" + reason + "。索引 " +
                    matcher().size + " 条 / 库里 " + db.questionDao().totalCount() + " 道；" +
                    "输入归一化后 " + Similarity.normalize(stem).length + " 字、去选项后 " +
                    key.length + " 字（" + key.take(16) + "）；" +
                    "库里和它完全对得上的 " + exact + " 条；最像的 " + outcome.probe.size +
                    " 条：" + near.ifEmpty { "（一条都没算出来）" },
            )
            return BankMatchOutcome.EMPTY.copy(genericOnly = outcome.genericOnly)
        }

        // 只为命中的那几条把整行捞回来（选项、答案、解析），其余候选不进内存
        val ids = outcome.hits.map { it.entry.id }
        val rows = db.questionDao().byIds(ids).associateBy { it.id }
        val bankNames = db.bankDao().all().associate { it.id to it.name }

        fun toMatch(hit: StemHit): QuestionMatch? {
            val question = rows[hit.entry.id] ?: return null
            return QuestionMatch(question, bankNames[question.bankId].orEmpty(), hit.score)
        }

        val ranked = outcome.hits.mapNotNull { hit ->
            val question = rows[hit.entry.id] ?: return@mapNotNull null
            QuestionMatch(
                question = question,
                bankName = bankNames[question.bankId].orEmpty(),
                score = hit.score,
                analysisScore = analysisScoreOf(stem, question, withAnalysis),
            )
        }
            // 「不重复」：同一道题不会因为两条路线都对上就在列表里出现两次
            .distinctBy { it.question.id }
            // 没开解析时不重排，保持匹配器给的原样（分数从高到低）
            .let { list ->
                if (withAnalysis) {
                    AnalysisRank.sorted(list, { it.score }, { it.analysisScore })
                } else {
                    list
                }
            }

        // margin / runnerUp / hesitation 一律照旧取匹配器算的那份（纯题干口径）：
        // 采信与否那道闸门只能建在题干上，解析只负责把列表里的先后挪一挪。
        return BankMatchOutcome(
            matches = ranked.take(limit),
            margin = outcome.margin,
            runnerUp = outcome.runnerUp?.let(::toMatch),
            hesitation = outcome.hesitation,
            genericOnly = outcome.genericOnly,
        )
    }

    /**
     * 这段输入和那条题解析的相似度。解析空着、或者没开这个功能时都是 0。
     *
     * 方向和拍题一致：第一个参数是「手上这段字」，第二个是「题库里那条正文」。
     */
    private fun analysisScoreOf(text: String, question: QuestionEntity, withAnalysis: Boolean): Double {
        if (!withAnalysis) return 0.0
        val analysis = question.analysis
        if (analysis.isBlank()) return 0.0
        return Similarity.matchScore(text, analysis)
    }

    /** 匹配索引：懒加载，题库一有写入就置空。 */
    @Volatile
    private var cachedMatcher: StemMatcher? = null

    /**
     * 「题库改过几次」的计数器。
     *
     * 只用来堵一个竞争：[matcher] 读题干、建索引要几毫秒，如果这期间正好有一次写入
     * （收了一道题、删了一条、整理了一次），那次 `invalidateMatcher` 会**先于**索引建完发生 ——
     * 建完的那份过期索引又把缓存填回去，于是它一直用旧索引，新收的题怎么拍都匹配不上
     * （「收录说已经收过、拍题说没有」正是这个形态）。建索引前后对一下号就能堵住：
     * 号变了就只**用**这一次、不缓存，下一次自然重建。
     */
    @Volatile
    private var matcherGeneration = 0

    private suspend fun matcher(): StemMatcher {
        cachedMatcher?.let { return it }
        val rows = db.questionDao().stems()
        // 索引里的条数必须和库里的题数对得上。对不上就是「明明库里有、拍题却说没有」
        // 那类报障的头号嫌疑（2026-10-12 用户报过一次），所以留一行警告。
        val total = db.questionDao().totalCount()
        if (rows.size != total) {
            Log.w(TAG, "匹配索引只有 " + rows.size + " 条题干，而库里有 " + total + " 道题 —— 索引没跟上")
        }
        // 短条目要把它自己的选项一起带上：判「这次命中是不是同一道题」时，
        // 输入里有没有这条题自己的选项是最硬的证据（2026-10-27「防寒过冬」那个库的报障，
        // 详见 StemMatcher.looksGenericOnly 第三条）。
        // 只给短条目补：长题干不需要，几千道题的选项全塞进索引会白吃几个 MB。
        val shortIds = rows.filter { StemMatcher.isShortEntry(it.stem) }.map { it.id }
        val optionsById = if (shortIds.isEmpty()) {
            emptyMap()
        } else {
            shortIds.chunked(BATCH_LIMIT).flatMap { db.questionDao().byIds(it) }
                .associate { it.id to it.options }
        }
        val entries = rows.map {
            StemEntry(it.id, it.bankId, it.stem, optionsById[it.id].orEmpty())
        }
        val generation = matcherGeneration
        // 归一化几千条题干是纯 CPU 活，而拍题流水线跑在 Dispatchers.Main 上，
        // 放在主线程会把悬浮窗卡住几十毫秒（题库再大一点就是 ANR）。
        val built = withContext(Dispatchers.Default) { StemMatcher(entries) }
        // 建索引的这几毫秒里题库要是又被改过，这份就是过期的：只给这一次用，不缓存
        if (generation == matcherGeneration) {
            cachedMatcher = built
        } else {
            Log.i(TAG, "匹配索引建好时题库已经被改过，这次不缓存（下次重建）")
        }
        return built
    }

    private fun invalidateMatcher() {
        cachedMatcher = null
        matcherGeneration++
    }

    suspend fun searchAll(keyword: String, limit: Int = 50): List<QuestionMatch> {
        if (keyword.isBlank()) return emptyList()
        val bankNames = db.bankDao().all().associate { it.id to it.name }
        return db.questionDao().searchAll(keyword, limit).map {
            QuestionMatch(it, bankNames[it.bankId].orEmpty(), 1.0)
        }
    }

    // ------------------------------------------------------------------ 收录

    /**
     * 题库里一条「只有半截」的旧条目 —— 早期收录还没有共用题干那套流程时，
     * 材料没存进去，只留下一句小问（见 [com.daily.quizbank.util.FragmentRepair]）。
     */
    data class FragmentCandidate(val id: Long, val stem: String)

    /**
     * 收录完成后弹窗里摆出来的「库里已经有的那条」。
     *
     * [reason] 是给他判断用的一句话：「一模一样」/「相似度 95%」/
     * 「是这次这条的一部分 —— 像上次没收完留下的那半截」。光说一句「有重复」他没法决定留哪条。
     */
    data class OldCandidate(
        val id: Long,
        val stem: String,
        val answer: String,
        val options: List<String>,
        val analysis: String,
        val reason: String,
    )

    /**
     * 收录前查出来的「库里那条 + 它像在哪」。
     *
     * 扫描阶段只带 [StemRow]（id / bankId / 题干三列）—— 要摆到弹窗上的那几条最后按 id 取全文。
     */
    private class SimilarHit(val row: StemRow, val reason: String, val score: Double)

    /** 收录一道题的结果。 */
    data class CollectResult(
        val saved: Boolean,
        /** 给用户看的一句话。 */
        val message: String,
        /** 这次收的题干（摆出来和库里那几条对照着看）。 */
        val newStems: List<String> = emptyList(),
        /** 这次真正入库那几条的 id —— 「留旧的、把刚收的删掉」要用它。 */
        val savedIds: List<Long> = emptyList(),
        /**
         * 库里和这次收的**极相似**的那几条，加被这次补全的半截旧条目，按 id 去重。
         *
         * 是**一串**而不是一条：共用题干题一次收好几个小问，可能一次撞上好几条旧的。
         * **删东西不替用户决定**：这里只负责认出来、列出来，弹不弹、删不删由界面问他。
         */
        val oldCandidates: List<OldCandidate> = emptyList(),
    )

    /**
     * 收录之前先看一眼：这批新题干在**目标题库里**已经有哪些极相似的旧题。
     *
     * 三档，判据和「找出相似题」完全一样（见 [DedupPolicy]）：
     *  1. 归一化之后完全相同；
     *  2. 分桶后 [Similarity.score] ≥ [SIMILAR_WARN_THRESHOLD] —— 收录这一档比导入低一点
     *     （0.85 对 0.88）：收录的题干是 OCR 出来的，同一个字认成两个样是常事；
     *  3. **一条几乎整个出现在另一条里**（[DedupPolicy.isPartOf]）—— 「上次没收完的那半截」。
     *     2026-10-13 用户报的就是这一种：同一道共用题干题收了两三次，前面几次材料只拍了一半。
     *
     * 为什么不能沿用原来那句 [match]：那个在**全库**里找一个最高分，只回一条、没有 id，
     * 于是既列不出「和谁重复」，也没法让用户选「留哪条、删哪条」。
     *
     * **只查目标题库**（多半是「拍题收藏」）：跨库的题不该被这一路的删除按钮删掉 ——
     * 他把同一道题收在两个库里往往是有意的（和 [findFragmentsSuperseded] 一个道理）。
     * 这一步**必须在入库之前**跑，否则刚存进去的那条会和自己撞上。
     */
    private suspend fun findSimilarBeforeCollect(
        bankId: Long,
        stems: List<String>,
        limit: Int = 5,
    ): List<OldCandidate> {
        if (stems.isEmpty()) return emptyList()
        val dao = db.questionDao()
        // 扫描只用题干三列；要摆到界面上的那几条最后按 id 取回全文
        val rows = dao.stems().filter { it.bankId == bankId }
        if (rows.isEmpty()) return emptyList()

        val byExact = HashMap<String, StemRow>()
        val buckets = HashMap<String, MutableList<StemRow>>()
        val containBuckets = HashMap<String, MutableList<StemRow>>()
        for (row in rows) {
            val norm = Similarity.normalize(row.stem)
            if (norm.isNotEmpty()) byExact.putIfAbsent(norm, row)
            val key = DedupPolicy.bucketKey(row.stem)
            if (DedupPolicy.bucketUsable(key)) buckets.getOrPut(key) { ArrayList() }.add(row)
            for (window in DedupPolicy.containKeys(row.stem)) {
                containBuckets.getOrPut(window) { ArrayList() }.add(row)
            }
        }

        val hits = LinkedHashMap<Long, SimilarHit>()
        for (stem in stems) {
            byExact[Similarity.normalize(stem)]?.let { row ->
                hits.putIfAbsent(row.id, SimilarHit(row, "一模一样（去掉标点和空白后完全相同）", 1.0))
            }
            val key = DedupPolicy.bucketKey(stem)
            if (DedupPolicy.bucketUsable(key)) {
                var best: StemRow? = null
                var bestScore = 0.0
                for (row in buckets[key].orEmpty()) {
                    val score = Similarity.score(row.stem, stem)
                    if (score >= SIMILAR_WARN_THRESHOLD && score > bestScore) {
                        best = row
                        bestScore = score
                    }
                }
                best?.let { row ->
                    hits.putIfAbsent(
                        row.id,
                        SimilarHit(row, "相似度 " + (bestScore * 100).toInt() + "%", bestScore),
                    )
                }
            }
            val seen = HashSet<Long>()
            for (window in DedupPolicy.containKeys(stem)) {
                for (row in containBuckets[window].orEmpty()) {
                    if (!seen.add(row.id)) continue
                    if (!DedupPolicy.isPartOf(row.stem, stem)) continue
                    hits.putIfAbsent(
                        row.id,
                        SimilarHit(
                            row,
                            "是这次这条的一部分 —— 像上次没收完留下的那半截",
                            DedupPolicy.CONTAIN_COVERAGE,
                        ),
                    )
                }
            }
        }
        if (hits.isEmpty()) return emptyList()

        val ranked = hits.values.sortedByDescending { it.score }.take(limit)
        val full = ranked.map { it.row.id }.chunked(BATCH_LIMIT)
            .flatMap { dao.byIds(it) }
            .associateBy { it.id }
        return ranked.mapNotNull { hit ->
            val row = full[hit.row.id] ?: return@mapNotNull null
            OldCandidate(
                id = row.id,
                stem = row.stem,
                answer = row.answer,
                options = row.options,
                analysis = row.analysis,
                reason = hit.reason,
            )
        }
    }

    /** 弹窗里那些「库里的旧条目」：极相似的 + 被这次补全的半截，按 id 去重（相似那条优先）。 */
    private fun oldCandidatesOf(
        similar: List<OldCandidate>,
        fragments: List<FragmentCandidate>,
    ): List<OldCandidate> {
        val out = LinkedHashMap<Long, OldCandidate>()
        for (candidate in similar) out[candidate.id] = candidate
        for (fragment in fragments) {
            out.putIfAbsent(
                fragment.id,
                OldCandidate(
                    id = fragment.id,
                    stem = fragment.stem,
                    answer = "",
                    options = emptyList(),
                    analysis = "",
                    reason = "只有半截的旧条目（材料没存进去的那份）",
                ),
            )
        }
        return out.values.toList()
    }

    /**
     * 把一道题收进「拍题收藏」。
     *
     * 走的是和文件导入**完全相同**的入库路径（[importDrafts]），所以去重规则、
     * 重复判定的口径都一致 —— 从两条路进来的题不会出现「一边说重复、一边又收进去」的分裂。
     *
     * 除了「收没收到」，还会顺手查一遍**相似题**：
     * 入库用的判重是「去掉空白后完全相等」，只差一个标点的近似题它认不出来，
     * 而那种题正是最该提醒用户注意的（两份答案可能不一样）。
     * 相似度匹配是现成的，顺手跑一次几乎不花时间。
     */
    suspend fun saveCollected(
        stem: String,
        options: List<String>,
        answer: String,
        analysis: String,
        bankName: String = COLLECTED_BANK_NAME,
    ): CollectResult {
        val bankId = createBank(bankName, subject = "收录")
        // 答案统一成「大写字母、去重排序」：用户打 b / B / 小写混着来都行，
        // 「A,C」「A、C」也都可以；判断题的「对 / √ / 是」统一成「正确」。
        val cleanAnswer = QuestionParser.normalizeAnswer(answer)
        // 题型必须自己推 —— 这里原来写死成「有选项就算单选题」，
        // 于是多选题和判断题全被贴上了单选题的标签。
        val type = QuestionParser.inferType(stem, options, cleanAnswer)

        // 「这一条把库里哪条半截旧条目补全了」必须在**入库之前**查。
        //
        // 2026-10-12 用户报的毛病就是这个：他每收一道**短题**（归一化不到 30 字的单选题，
        // 他库里占四成），收完都弹出「要不要顶掉旧的」，他一点「顶掉」，**刚收的那道题
        // 自己就被删了** —— 因为入库之后再查，新存的这条自己也是一条「不到 30 字的半截」，
        // 而它当然「完整包含它自己」。拿他 2026-10-09 导出的 175 道真题量过：**21 道会自顶自**。
        // 改成入库之前查，查出来的必然是**先前就存在**的那些条目（见 findFragmentsSuperseded）。
        val superseded = findFragmentsSuperseded(listOf(stem), bankId)
        // 库里跟它极相似的（一模一样的 / 相似度够高的 / 上次没收完的那半截）——也在入库之前查
        val similar = findSimilarBeforeCollect(bankId, listOf(stem))

        val summary = importDrafts(
            bankId,
            listOf(
                QuestionDraft(
                    stem = stem,
                    options = withJudgeOptions(type, options),
                    answer = cleanAnswer,
                    analysis = analysis,
                    type = type,
                    tags = "收录",
                ),
            ),
        )

        val saved = summary.inserted > 0
        // 刚存的这条要是把库里某条半截旧条目「补全」了，记下来让界面问一句。
        // 这一查发生在入库之前（见上面 superseded 那段注释），所以绝不会查到刚存的这条自己。
        val replaced = if (saved) superseded else emptyList()
        val candidates = oldCandidatesOf(similar, replaced)
        val message = buildString {
            append(if (saved) "已存入「" else "这道题已经在「")
            append(bankName)
            append(if (saved) "」" else "」里了")
            if (candidates.isNotEmpty()) {
                append("\n\n题库里已经有 ")
                append(candidates.size)
                append(" 条和它很像的题：")
                append(candidates.first().stem.take(30))
                if (candidates.size > 1) append(" 等")
            }
        }
        return CollectResult(
            saved = saved,
            message = message,
            newStems = listOf(stem),
            savedIds = summary.insertedIds,
            oldCandidates = candidates,
        )
    }

    /**
     * 这一批新存的题干，把题库里哪些**半截旧条目**补全了。
     *
     * 只看同一个题库：跨库的题不该被悄悄删掉 —— 用户把同一道题收在两个库里是有意的。
     * 每条新题干最多认一条旧的（最短的那条，越短越像「只有一句小问」）。
     *
     * 2026-10-09 踩过一次：这段一开始只挂在**普通题**的保存路径上，而用户是拿
     * **共用题干模式**重收的（一个材料带几个小问），于是收完了什么都不问。
     * 现在两条保存路径都走这里（见 [saveCollectedMulti]）。
     */
    private suspend fun findFragmentsSuperseded(
        newStems: List<String>,
        bankId: Long,
    ): List<FragmentCandidate> {
        if (newStems.isEmpty()) return emptyList()
        val rows = withContext(Dispatchers.IO) { db.questionDao().byBank(bankId) }
        // 和这次新收的题干**一模一样**的条目不算「旧条目」——
        // 就算这个函数哪天又被挪到入库之后调用，也不会再出现「新收的把自己顶掉」。
        val freshKeys = newStems.mapTo(HashSet()) { Similarity.normalize(it) }
        val fragments = rows.filter {
            Similarity.normalize(it.stem) !in freshKeys && FragmentRepair.looksLikeFragment(it.stem)
        }
        if (fragments.isEmpty()) return emptyList()
        val hit = LinkedHashMap<Long, FragmentCandidate>()
        for (fresh in newStems) {
            fragments
                .filter { FragmentRepair.supersedes(it.stem, fresh) }
                .minByOrNull { Similarity.normalize(it.stem).length }
                ?.let { hit[it.id] = FragmentCandidate(it.id, it.stem) }
        }
        // 打一行日志：用户报「它把我题删了」时，这是唯一能查出「到底要删哪几条」的东西
        Log.i(
            TAG,
            "收录：怀疑有 " + hit.size + " 条半截旧条目被这次收全了（要删也是用户点）" +
                hit.values.joinToString("") { " | 「" + it.stem.replace("\n", " ").take(24) + "」" },
        )
        return hit.values.toList()
    }

    /**
     * 收录一道**共用题干题**：一个大题干（材料 / 病例）带若干小问，每小问各存一条。
     *
     * 为什么不是整段存成一条：用户多半是分开拍每一小问的（刷题软件一屏一题），
     * 整段存进去的话，单独拍第 2 问时题库里没有任何一条能对上。
     * 所以每条都把大题干拼在自己题干前面 —— 信息不丢，单独拍也能匹配。
     *
     * 同一组的几条共用一个 tags 标记，题库列表里能认出它们是一家人。
     * 用 tags 承载而不是新加字段：不动表结构就免了一次数据库迁移，
     * 而迁移正是最容易出事的地方（见 AppDatabase 的破坏性兜底）。
     */
    suspend fun saveCollectedMulti(
        shared: String,
        parts: List<QuestionDraft>,
        groupTag: String,
        bankName: String = COLLECTED_BANK_NAME,
    ): CollectResult {
        val bankId = createBank(bankName, subject = "收录")
        val drafts = parts.map { part ->
            // 每一小问各推各的题型：同一组材料里同时有单选、多选、判断是常事
            val cleanAnswer = QuestionParser.normalizeAnswer(part.answer)
            val type = QuestionParser.inferType(part.stem, part.options, cleanAnswer)
            part.copy(
                stem = shared + "\n\n" + part.stem,
                options = withJudgeOptions(type, part.options),
                answer = cleanAnswer,
                type = type,
                tags = if (part.tags.isBlank()) groupTag else part.tags + " " + groupTag,
            )
        }
        // 和单题那条路一样：也在**入库之前**查（理由见 saveCollected 里那段注释）——
        // 一收几个小问时更容易撞上「自己顶自己」。
        val superseded = findFragmentsSuperseded(drafts.map { it.stem }, bankId)
        // 库里跟这几个小问极相似的：一模一样 / 相似度够高 / **上次没收完留下的那半截**。
        // 用户 2026-10-13 报的就是最后这一种 —— 前面两次没收完就点了确定，库里躺着半截的旧条目。
        val similar = findSimilarBeforeCollect(bankId, drafts.map { it.stem })

        val summary = importDrafts(bankId, drafts)
        val inserted = summary.inserted
        val replaced = if (inserted > 0) superseded else emptyList()
        val candidates = oldCandidatesOf(similar, replaced)
        return CollectResult(
            saved = inserted > 0,
            newStems = drafts.map { it.stem },
            savedIds = summary.insertedIds,
            oldCandidates = candidates,
            message = buildString {
                append("已存入「")
                append(bankName)
                append("」")
                append(inserted)
                append(" 道（共用题干，这一组共 ")
                append(drafts.size)
                append(" 小问）")
                if (summary.duplicates > 0) {
                    append("\n其中 ")
                    append(summary.duplicates)
                    append(" 道之前已经收过了，跳过")
                }
                if (candidates.isNotEmpty()) {
                    append("\n\n题库里已经有 ")
                    append(candidates.size)
                    append(" 条和它很像的题：")
                    append(candidates.first().stem.take(30))
                    if (candidates.size > 1) append(" 等")
                }
            },
        )
    }

    // ------------------------------------------------------------------ 合并题库

    /**
     * 合并预览里的一组重复题：至少两条，**跨两个库**。
     *
     * [key] 是**分桶键**（第二档是「去掉题号取前 8 个字」），**不是唯一标识** ——
     * 同一个桶里可能生出好几组（开头八个字一样的近似题）。它只用来判「这一组是不是
     * 一模一样」，**绝不能拿去当列表的 key**：2026-10-09 用户拖题库合并时闪退，
     * 报的就是 `Key "男70岁直肠指诊" was already used`。列表和「选了留哪条」请用下标。
     */
    data class MergeGroup(val key: String, val items: List<QuestionEntity>) {

        /**
         * 用户没指定时默认留哪条：**先留目标库里那条**（合并是「并进去」，不是「顶掉」），
         * 同一个库里再挑信息最全的（有解析 > 有选项 > 有答案）。
         * 界面上的初始选中项和仓库的兜底都走这里，两边口径才不会分叉。
         */
        fun preferred(targetId: Long): QuestionEntity =
            items.maxByOrNull { question ->
                var score = 0
                if (question.bankId == targetId) score += 100
                if (question.analysis.isNotBlank()) score += 4
                if (question.options.isNotEmpty()) score += 2
                if (question.answer.isNotBlank()) score += 1
                score
            } ?: items.first()
    }

    /**
     * 一次合并的预览。用户看过它、逐组选完「留哪条」之后才会真的动数据。
     *
     * [groups] 里每一组都至少有一条来自源库、一条来自目标库 —— 只在同一个库里重复的题
     * 不算合并冲突（那是题库自己该清理的，合并这一步不替它做主）。
     */
    data class MergePreview(
        val sourceId: Long,
        val sourceName: String,
        val sourceCount: Int,
        val targetId: Long,
        val targetName: String,
        val targetCount: Int,
        val groups: List<MergeGroup>,
    ) {
        /** 牵扯到的重复题一共几条（每组至少 2 条）。 */
        val duplicateCount: Int get() = groups.sumOf { it.items.size }

        /** 按「每组留一条」算，合并后会少掉几条。 */
        val removableCount: Int get() = groups.sumOf { it.items.size - 1 }

        /** 一条重复都没有时要搬过去的题数。 */
        val movedCount: Int get() = sourceCount - groups.sumOf { g -> g.items.count { it.bankId == sourceId } }
    }

    /** 合并完成后的交代：搬了多少、去重删了多少、落到哪个库。 */
    data class MergeSummary(
        val targetName: String,
        val moved: Int,
        val removed: Int,
    )

    suspend fun mergePreview(sourceId: Long, targetId: Long): MergePreview? {
        if (sourceId == targetId) return null
        val source = db.bankDao().byId(sourceId) ?: return null
        val target = db.bankDao().byId(targetId) ?: return null
        val a = db.questionDao().byBank(sourceId)
        val b = db.questionDao().byBank(targetId)
        return MergePreview(
            sourceId = sourceId,
            sourceName = source.name,
            sourceCount = a.size,
            targetId = targetId,
            targetName = target.name,
            targetCount = b.size,
            groups = duplicateGroups(sourceId, a, b),
        )
    }

    /**
     * 真的合并。[keepIds] 是用户在预览里逐组挑出来的「留下这条」，没挑的组走 [preferredOf]。
     *
     * 源库在合并完之后会被删掉 —— 「合并」的意思就是它并进去了，留着只会让人以为还有两份。
     * 题目靠改 bankId 搬过去，id / 星标 / 时间都不动，所以收藏和引用不会断。
     */
    suspend fun mergeBanks(sourceId: Long, targetId: Long, keepIds: Set<Long>): MergeSummary? {
        if (sourceId == targetId) return null
        val source = db.bankDao().byId(sourceId) ?: return null
        val target = db.bankDao().byId(targetId) ?: return null
        val dao = db.questionDao()
        val a = dao.byBank(sourceId)
        val b = dao.byBank(targetId)
        val groups = duplicateGroups(sourceId, a, b)

        val deleteIds = ArrayList<Long>()
        for (group in groups) {
            val keep = group.items.firstOrNull { it.id in keepIds } ?: group.preferred(targetId)
            group.items.forEach { if (it.id != keep.id) deleteIds.add(it.id) }
        }

        val removed = HashSet(deleteIds)
        val moveIds = a.map { it.id }.filter { it !in removed }
        // SQLite 一条语句最多 999 个绑定变量，留点余量分批
        moveIds.chunked(BATCH_LIMIT).forEach { dao.moveToBank(it, targetId) }
        deleteIds.chunked(BATCH_LIMIT).forEach { dao.deleteByIds(it) }

        invalidateMatcher()
        db.bankDao().deleteById(source.id)
        db.bankDao().touch(target.id)
        Log.i(TAG, "合并题库：" + source.name + " → " + target.name +
            "，搬 " + moveIds.size + " 条，去重删 " + deleteIds.size + " 条")
        return MergeSummary(targetName = target.name, moved = moveIds.size, removed = deleteIds.size)
    }

    /**
     * 找出两个库之间的重复题，分两档：
     *
     *  1. **一模一样**：归一化（只留字母数字）之后完全相同。题库里真实的重复大多长这样 ——
     *     同一道题收了两遍，差别只在标点和空格（「…全列满水 A.14」和「…全列满水。 A.14」）。
     *  2. **疑似同一道**：分桶之后比相似度。分桶键是「去掉开头的题号、取前 8 个字」，
     *     题号会变（刷题软件的序号、OCR 多认的数字），去掉它才不至于把同一道题分到两个桶。
     *
     * 分桶是必要的：两个库各几千道时两两比是几百万次，手机上要跑好几分钟。
     * 代价是**开头八个字就不一样的近似题找不到** —— 那种情况本来就该由用户自己看一眼。
     */
    private fun duplicateGroups(
        sourceId: Long,
        source: List<QuestionEntity>,
        target: List<QuestionEntity>,
    ): List<MergeGroup> {
        val groups = ArrayList<MergeGroup>()
        val taken = HashSet<Long>()

        // 第一档：归一化之后完全一样
        val exact = LinkedHashMap<String, MutableList<QuestionEntity>>()
        for (question in source + target) {
            val key = Similarity.normalize(question.stem)
            if (key.length < EXACT_MIN_LENGTH) continue
            exact.getOrPut(key) { ArrayList() }.add(question)
        }
        for ((key, items) in exact) {
            if (items.size < 2) continue
            if (items.none { it.bankId == sourceId }) continue
            if (items.none { it.bankId != sourceId }) continue
            groups.add(MergeGroup(key, items))
            items.forEach { taken.add(it.id) }
        }

        // 第二档：同一桶里再比一遍相似度
        val buckets = LinkedHashMap<String, MutableList<QuestionEntity>>()
        for (question in source + target) {
            if (question.id in taken) continue
            val key = blockKey(question.stem)
            if (!DedupPolicy.bucketUsable(key)) continue
            buckets.getOrPut(key) { ArrayList() }.add(question)
        }
        for ((key, items) in buckets) {
            val left = items.filter { it.bankId == sourceId }
            val right = items.filter { it.bankId != sourceId }
            if (left.isEmpty() || right.isEmpty()) continue
            for (one in left) {
                if (one.id in taken) continue
                val same = right.filter {
                    it.id !in taken && Similarity.score(it.stem, one.stem) >= DedupPolicy.NEAR_DUPLICATE
                }
                if (same.isEmpty()) continue
                groups.add(MergeGroup(key, listOf(one) + same))
                taken.add(one.id)
                same.forEach { taken.add(it.id) }
            }
        }
        return groups
    }

    /**
     * 去掉开头的题号再取前几个字 —— 分桶用。
     *
     * 实现只有 [DedupPolicy.bucketKey] 一处：合并题库、找相似题、导入判重三处口径必须一样，
     * 否则同一对题会在「合并」里说重复、在「导入」里说不重复。
     */
    private fun blockKey(stem: String): String = DedupPolicy.bucketKey(stem)

    // ------------------------------------------------------------------ 相似题（整理题库）

    /**
     * 题库里一组「像到该留一条」的题。
     *
     * 和合并预览的 [MergeGroup] 是**两件事**：那个只在两个库之间找重复（合并时会去重），
     * 这个查的是**同一个题库之内**的重复 —— 早期收录时同一道题收了两遍、OCR 换了个错字
     * 又来一遍，都会在库里留下两条几乎一样的题，合并题库帮不上忙。
     */
    data class SimilarGroup(
        /** 分桶键。**不是唯一标识，绝不能拿去当列表的 key**（见 [MergeGroup] 的说明）。 */
        val key: String,
        /** 这一组落在哪个题库 —— 组是按题库切开的，所以一组只有一个库名。 */
        val bankName: String,
        /** 至少两条，条条都在同一个题库里。 */
        val items: List<QuestionEntity>,
        /** true = 归一化之后一模一样；false = 算出来的相似度够高（会错，界面上要标出来）。 */
        val exact: Boolean,
        /**
         * true = 这一组是「**一条几乎整个出现在另一条里**」（[DedupPolicy.isPartOf]）。
         *
         * 这就是「同一条题上次没收完」留下的半截：材料只拍了一半、或者只填了小问没填大题干。
         * 这种两条的整串相似度只有五成上下，前两档都够不到 —— 2026-10-13 用户报的
         * 「找相似题说没有相似题」就是这个形态。
         */
        val partial: Boolean = false,
    ) {
        /**
         * 用户没点之前默认留哪条：**信息最全的那条**（有解析 > 有选项 > 有答案），
         * 并列时留先收进来的那条。
         *
         * 不用「id 大的」或者「最后一条」：题库里真实的情况是「先收的那条 OCR 更差、
         * 后补的那条更全」，而信息多寡是能直接判的，收进来的先后判不出来。
         */
        fun preferred(): QuestionEntity = items.maxByOrNull { score(it) } ?: items.first()

        private fun score(question: QuestionEntity): Int {
            var value = 0
            if (question.analysis.isNotBlank()) value += 4
            if (question.options.isNotEmpty()) value += 2
            if (question.answer.isNotBlank()) value += 1
            // 题干长的多半是「材料 + 小问」完整的那个，短的往往是资料没存进去的半截条目
            if (question.stem.length >= SHORT_ENTRY_CHARS) value += 2
            return value
        }
    }

    /** 一次「找相似题」的结果。 */
    data class SimilarReport(
        /** 扫了多少道题。 */
        val scanned: Int,
        val groups: List<SimilarGroup>,
        /** 组数超过上限、只列了前几组。 */
        val truncated: Boolean,
        /**
         * **没成组**的那些配对里最像的一对有多像。
         *
         * 用户报「明明有两道一样的题，它说没有」时，光看「没有相似题」什么也查不出来；
         * 有这两个数就能一眼看出是「差一点没到线」还是「压根不像」——
         * 和拍题那条「匹配：一条都没到展示线…」是同一个套路（2026-10-12 那次就靠它定性的）。
         */
        val bestMissScore: Double = 0.0,
        val bestMissCoverage: Double = 0.0,
    ) {
        /** 按「每组留一条」算，清理后最多能少掉几条。 */
        val removable: Int get() = groups.sumOf { it.items.size - 1 }

        /** 第三档（半截）找出来几组。 */
        val partialCount: Int get() = groups.count { it.partial }
    }

    /**
     * 扫一遍整个题库，把相似度特别高的题**成组**列出来（2026-10-12 用户要的「整理题库」新功能）。
     *
     * 相似是**程序算的**（[Similarity] + 分桶），不是让 AI 去逐条读 ——
     * AI 分不出「哪两条像」，只会给你一段听起来很有道理的话。
     *
     * 判据分三档（前两档和合并预览完全一致）：
     *  1. 归一化（只留字母数字）之后完全相同 —— 题库里真实的重复大多长这样；
     *  2. 去掉题号取前 8 个字分桶，同桶内 [Similarity.score] ≥ [threshold]（默认 0.88）；
     *  3. **一条几乎整个出现在另一条里**（[DedupPolicy.isPartOf]）—— 「同一条题上次没收完」
     *     留下的那半截。这一档是 2026-10-13 补的：用户收一道共用题干题时分两次都没收完就点了确定，
     *     最后一次收全了，回头点「找出相似题」却是「没有相似题」—— 那几条的整串相似度只有五成上下，
     *     前两档都够不到。**这一档还要求两条的结尾是同一句小问**，否则同一个材料下面的
     *     第 1 问和第 2 问也会被判成重复（它们互相的覆盖率就有 0.9 上下）。
     *
     * 分桶是为了不两两比：上千道题两两比是几十万次，手机上要卡住好几秒。
     * 前两档的代价是**开头八个字就不一样的近似题找不到**；第三档改用「每 8 个字挂一个窗口」
     * 的多窗口分桶（[DedupPolicy.containKeys]），半截那条从中间某个字开始也能配对。
     *
     * **只找同一个题库之内的重复**：跨库的重复是「合并题库」该管的事，两边都列会让用户
     * 在合并时被同一个问题问两遍。
     */
    suspend fun findSimilarQuestions(
        threshold: Double = DedupPolicy.NEAR_DUPLICATE,
        limit: Int = SIMILAR_GROUP_LIMIT,
    ): SimilarReport {
        val all = db.questionDao().all()
        val bankNames = db.bankDao().all().associate { it.id to it.name }
        fun bankOf(question: QuestionEntity): String =
            bankNames[question.bankId] ?: "未命名题库"

        val groups = ArrayList<SimilarGroup>()
        val taken = HashSet<Long>()
        var truncated = false
        // 没成组的配对里最像的那一对：用户报「没有相似题」时要靠它定性（见 SimilarReport）
        var bestMissScore = 0.0
        var bestMissCoverage = 0.0

        // 第一档：归一化之后完全一样
        val exact = LinkedHashMap<String, MutableList<QuestionEntity>>()
        for (question in all) {
            val key = Similarity.normalize(question.stem)
            if (key.length < EXACT_MIN_LENGTH) continue
            exact.getOrPut(key) { ArrayList() }.add(question)
        }
        for ((key, items) in exact) {
            if (items.size < 2) continue
            // 同一个键下可能横跨好几个题库，按题库切开，各自成组
            for ((_, sameBank) in items.groupBy { it.bankId }) {
                if (sameBank.size < 2) continue
                if (groups.size >= limit) { truncated = true; break }
                groups.add(SimilarGroup(key, bankOf(sameBank.first()), sameBank, exact = true))
                sameBank.forEach { taken.add(it.id) }
            }
        }

        // 第二档：分桶之后比相似度
        val buckets = LinkedHashMap<String, MutableList<QuestionEntity>>()
        for (question in all) {
            if (question.id in taken) continue
            val key = blockKey(question.stem)
            if (!DedupPolicy.bucketUsable(key)) continue
            buckets.getOrPut(key) { ArrayList() }.add(question)
        }
        for ((key, items) in buckets) {
            if (groups.size >= limit) { truncated = true; break }
            for (question in items) {
                if (question.id in taken) continue
                val same = ArrayList<QuestionEntity>()
                for (other in items) {
                    if (other.id == question.id || other.id in taken) continue
                    if (other.bankId != question.bankId) continue
                    val score = Similarity.score(other.stem, question.stem)
                    if (score >= threshold) same.add(other) else if (score > bestMissScore) bestMissScore = score
                }
                if (same.isEmpty()) continue
                groups.add(
                    SimilarGroup(key, bankOf(question), listOf(question) + same, exact = false),
                )
                taken.add(question.id)
                same.forEach { taken.add(it.id) }
            }
        }

        // 第三档：一条几乎整个出现在另一条里 —— 同一条题上次没收完留下的那半截。
        //
        // 分桶不能只看开头八个字（半截那条可能从中间某个字开始），所以一条题挂在好几个 8 字窗口下面。
        // 一对题只算一次，用下标对儿去重（id 拼字符串，避免乘法溢出）。
        val containBuckets = LinkedHashMap<String, MutableList<QuestionEntity>>()
        for (question in all) {
            if (question.id in taken) continue
            for (key in DedupPolicy.containKeys(question.stem)) {
                containBuckets.getOrPut(key) { ArrayList() }.add(question)
            }
        }
        val testedPairs = HashSet<String>()
        for ((key, items) in containBuckets) {
            if (groups.size >= limit) { truncated = true; break }
            if (items.size < 2) continue
            for (i in items.indices) {
                if (groups.size >= limit) { truncated = true; break }
                val one = items[i]
                if (one.id in taken) continue
                for (j in i + 1 until items.size) {
                    val other = items[j]
                    if (other.id in taken) continue
                    if (other.bankId != one.bankId) continue
                    val pair = if (one.id < other.id) one.id.toString() + "-" + other.id else other.id.toString() + "-" + one.id
                    if (!testedPairs.add(pair)) continue
                    val coverage = DedupPolicy.partCoverage(one.stem, other.stem)
                    if (coverage > bestMissCoverage) bestMissCoverage = coverage
                    if (coverage < DedupPolicy.CONTAIN_COVERAGE) continue
                    // 结尾对不上就不是「同一句小问」—— 同一个材料下的不同小问会栽在这一条上
                    if (!DedupPolicy.tailMatches(one.stem, other.stem)) continue
                    groups.add(
                        SimilarGroup(
                            key = key,
                            bankName = bankOf(one),
                            items = listOf(one, other),
                            exact = false,
                            partial = true,
                        ),
                    )
                    taken.add(one.id)
                    taken.add(other.id)
                    break
                }
            }
        }

        Log.i(
            TAG,
            "找相似题：扫描 " + all.size + " 道，列出 " + groups.size + " 组（一模一样 " +
                groups.count { it.exact } + " 组 / 近似 " + groups.count { !it.exact && !it.partial } +
                " 组 / 半截 " + groups.count { it.partial } + " 组），最多能少 " +
                groups.sumOf { it.items.size - 1 } + " 道；没成组的最像一对：相似度 " +
                (bestMissScore * 100).toInt() + "%、覆盖率 " + (bestMissCoverage * 100).toInt() + "%" +
                if (truncated) "（已截断）" else "",
        )
        return SimilarReport(
            scanned = all.size,
            groups = groups,
            truncated = truncated,
            bestMissScore = bestMissScore,
            bestMissCoverage = bestMissCoverage,
        )
    }

    /**
     * 用户在相似题页面上逐组选完之后真的删。
     *
     * **只删用户点过的那几条**：这里收的是界面算好的 id 列表，仓库不自己重算一遍
     * 「哪条该删」—— 列表是用户看着选的，重算一次万一条件变了，删的可就不是他看见的那些了。
     */
    suspend fun deleteQuestionsByIds(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val dao = db.questionDao()
        ids.chunked(BATCH_LIMIT).forEach { dao.deleteByIds(it) }
        invalidateMatcher()
        Log.i(TAG, "相似题清理：删掉 " + ids.size + " 道")
        return ids.size
    }


    // ------------------------------------------------------------------ 导出

    /**
     * 把一个题库导出成 CSV 文本。
     *
     * 列名用的就是导入器认得的那几个（题干 / 选项 / 答案 / 解析 / 题型 / 知识点 / 难度），
     * 所以导出的文件能被本应用**原样导回来** —— 用户之间分享时不用再交代格式，
     * 对方双击导入就行。
     *
     * 选项在一格里用换行分隔、整格加引号：CSV 里引号内的换行是标准允许的，
     * 本应用的 [com.daily.quizbank.data.import.DelimitedReader] 和 Excel 都认。
     * 不用分号或竖线拼，是因为选项正文里本来就可能出现这些符号。
     */
    /** 一次「整理题库」的结果。 */
    data class TidyReport(
        val scanned: Int,
        val fixed: Int,
        val samples: List<String>,
    )

    /**
     * 整理题库：把**已经存进去的**题目重新过一遍 OCR 清洗。
     *
     * 为什么需要它：清洗规则是在「收录那一刻」生效的，改规则救不了以前存下来的题。
     * 繁体字（納泵 / 細胞內液）、圈被认成选项字母（DD.）、字母粘在折行上（E行 + 行），
     * 全是后来才发现的，老题库里已经躺着了。攒到一千道再靠人手一条条改不现实，
     * 所以给一个能反复跑的批处理 —— 规则以后再改进，再点一次就行。
     *
     * 只改文字，不动 id / bankId / 星标 / 时间，外键关联和收藏都不会断。
     * 答案那一栏只走繁→简、不走整套清洗：多选题答案「AA」会被
     * [OcrText] 里「去掉重复字母」那条规则误伤。
     */
    suspend fun tidyStoredText(): TidyReport {
        val dao = db.questionDao()
        val all = dao.all()
        val samples = ArrayList<String>()
        var fixed = 0

        for (question in all) {
            var stem = OcrText.clean(question.stem)
            var options = question.options.map { OcrText.clean(it) }
            val analysis = OcrText.clean(question.analysis)
            // 答案那一栏只走繁→简 + 归一化，不走整套 OCR 清洗 ——
            // 多选题答案「AA」会被 OcrText 里「去掉重复字母」那条规则误伤。
            // 归一化顺带把大小写统一、把「A,C」理成「AC」、「对 / √」理成「正确」（2026-10-09 加的）。
            val answer = QuestionParser.normalizeAnswer(OcrText.toSimplified(question.answer))
            val type = betterType(question, answer)
            options = withJudgeOptions(type, options)

            // 把历史上被塞进题干的选项搬回选项栏。
            //
            // 2026-10-09 之前的收录界面只有一个叫「题干与选项」的输入框，
            // 一整块 OCR 原文（题干 + A. xx + B. xx…）全进了 stem，options 永远是空列表。
            // 结果：搜题只打题目正文时匹配被稀释、结果面板还原不出「C. 合资」、
            // 题库列表里那道题显示成一大坨。
            //
            // 只在 options 本来就空时才拆，而且不允许行内切（allowInline = false）——
            // 这条路上没有人在旁边复核，宁可漏拆也不能把一句正常的话切成三个「选项」。
            if (options.isEmpty()) {
                val (core, split) = QuestionParser.splitTrailingOptions(stem, allowInline = false)
                if (split.isNotEmpty() && core.isNotBlank()) {
                    stem = core
                    options = split.map { OcrText.clean(it) }
                }
            }

            val changed = stem != question.stem ||
                options != question.options ||
                analysis != question.analysis ||
                answer != question.answer ||
                type != question.type
            if (!changed) continue

            if (samples.size < TIDY_SAMPLES) {
                samples.add(
                    question.stem.replace('\n', ' ').take(20) +
                        "  →  " + stem.replace('\n', ' ').take(20) +
                        if (options.size > question.options.size) {
                            "（另拆出 " + (options.size - question.options.size) + " 个选项）"
                        } else {
                            ""
                        },
                )
            }
            dao.update(
                question.copy(
                    stem = stem,
                    options = options,
                    analysis = analysis,
                    answer = answer,
                    type = type,
                ),
            )
            fixed++
        }
        // 改过题干就得让匹配索引失效：索引里存的是**旧的**那批题干，
        // 不失效的话，整理完再拍题会拿新题干去比旧索引 —— 「库里明明有、拍题却说没有」。
        // 2026-10-12 补（这条一直没有，和缓存竞争那个坑是同一类问题）。
        if (fixed > 0) invalidateMatcher()
        // 打个日志：用户报「整理过了但好像没生效」的时候，这一行是唯一能查的东西
        Log.i(TAG, "整理题库：扫描 " + all.size + " 道，修正 " + fixed + " 道")
        return TidyReport(scanned = all.size, fixed = fixed, samples = samples)
    }

    /**
     * 判断题没框到选项时，补一对标准的「A. 正确 / B. 错误」。
     *
     * 不补会很难受：答案存的是「A」，结果面板就只能显示一个光秃秃的 A，
     * 而拍题时原题早就滚出屏幕了 —— 用户得自己回忆「A 是正确还是错误」。
     * 补上之后 [com.daily.quizbank.util.AnswerText.resolve] 就能还原成「A. 正确」。
     *
     * 只在**判断题**上补，而且只在选项本来为空时补 —— 用户自己框到的选项（「对 / 错」
     * 「是 / 否」）一律原样保留。「正确 / 错误」和「对 / 错」在 [AnswerText] 眼里是同一个意思，
     * 所以显示上不会有差别。
     */
    private fun withJudgeOptions(type: String, options: List<String>): List<String> =
        if (type == QuestionType.JUDGE && options.isEmpty()) JUDGE_OPTIONS else options

    /**
     * 整理老题库时对题型做一次**只升不降**的修正。
     *
     * 收录进来的题以前一律被标成单选题（见 [saveCollected] 的说明），多选题因此顶着一张
     * 单选的脸。但这里不能无脑重推 —— 导入的题库题型是从「题型」列抄来的，是用户自己的口径，
     * 重推会把它改掉（填空题最容易中枪：没有选项、答案又是一段话，重推会变成简答题）。
     *
     * 所以只在「现在是单选题 / 未分类」而且「新推的结果是多选题或判断题」时才改。
     */
    private fun betterType(question: QuestionEntity, answer: String): String {
        if (question.type != QuestionType.SINGLE && question.type != QuestionType.UNKNOWN) {
            return question.type
        }
        val guessed = QuestionParser.inferType(question.stem, question.options, answer)
        return if (guessed == QuestionType.MULTI || guessed == QuestionType.JUDGE) {
            guessed
        } else {
            question.type
        }
    }

    suspend fun exportCsv(bankId: Long): String {
        val rows = db.questionDao().byBank(bankId)
        val sb = StringBuilder()
        // 用 CRLF：Windows 版 Excel 对纯 LF 的 CSV 有时会把整份文件读成一行
        sb.append(EXPORT_HEADER).append("\r\n")
        for (q in rows) {
            sb.append(csvCell(q.stem)).append(',')
                .append(csvCell(q.options.joinToString("\n"))).append(',')
                .append(csvCell(q.answer)).append(',')
                .append(csvCell(q.analysis)).append(',')
                .append(csvCell(q.type)).append(',')
                .append(csvCell(q.tags)).append(',')
                .append(if (q.difficulty > 0) q.difficulty.toString() else "")
                .append("\r\n")
        }
        return sb.toString()
    }

    /** CSV 单元格转义：含逗号、引号或换行的整体加引号，内部引号翻倍。 */
    private fun csvCell(value: String): String {
        val text = value.trim()
        if (text.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return text
        return '"' + text.replace("\"", "\"\"") + '"'
    }

    // ------------------------------------------------------------------ 用量

    val usageSummary: Flow<UsageSummary> = db.usageDao().observeSummary()

    fun usageRecords(limit: Int = 200): Flow<List<UsageRecordEntity>> = db.usageDao().observeRecent(limit)

    /**
     * @param costMicros 传 null 就按内置的 DeepSeek 价目表（[Pricing]）估算。
     *   免费通道必须显式传 0 —— 价目表是 DeepSeek 的，套在别家的免费模型上
     *   会把不花钱的调用记成花了钱，用量页的数字就假了。
     */
    suspend fun recordUsage(
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        cacheHitTokens: Int,
        usedImage: Boolean,
        costMicros: Long? = null,
    ) {
        if (promptTokens <= 0 && completionTokens <= 0) return
        db.usageDao().insert(
            UsageRecordEntity(
                model = model,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cacheHitTokens,
                usedImage = usedImage,
                costMicros = costMicros
                    ?: Pricing.estimateMicros(promptTokens, completionTokens, cacheHitTokens),
            ),
        )
    }

    suspend fun clearUsage() = db.usageDao().clear()

    private companion object {

        /** 「整理题库」最多回报几条「改前 → 改后」的样例，够用户确认方向对就行。 */
        const val TIDY_SAMPLES = 3

        /** 合并题库时一条 SQL 最多带多少个 id（SQLite 的上限是 999，留点余量）。 */
        const val BATCH_LIMIT = 800

        /** 归一化之后短于这个长度就不当重复题 —— 太短的字串撞车是常态，不是重复。 */
        const val EXACT_MIN_LENGTH = 4

        const val TAG = "QuestionRepository"

        /** 导出用的表头。顺序和 [exportCsv] 写列的顺序必须一致。 */
        const val EXPORT_HEADER = "题干,选项,答案,解析,题型,知识点,难度"

        /** 判断题没框到选项时补的那一对。 */
        val JUDGE_OPTIONS = listOf("A. 正确", "B. 错误")

        /** 收录进来的题都放这个题库，和文件导入的题库分开。 */
        const val COLLECTED_BANK_NAME = "拍题收藏"

        /** 「找相似题」一次最多列多少组。再多用户也看不完，先清完这批再说。 */
        const val SIMILAR_GROUP_LIMIT = 200

        /**
         * 题干长到这个字数就认为它是「材料 + 小问」完整的那条。
         *
         * 和 StemMatcher 里那条「残缺条目」的线是同一个数（30 字）：留哪条时用它比长短，
         * 两条近似题里长的那条多半是资料齐全的。
         */
        const val SHORT_ENTRY_CHARS = 30

        /** 搜题要按解析重排时，先捞这么多条候选再排。 */
        const val ANALYSIS_POOL = 12

        // 排序那点规矩（加多少分、怎么排）都在 util/AnalysisRank.kt 里，那边是纯计算、能单测

        /** 相似度高到这个程度就值得提醒一句。低于它的大多是巧合的字面重合。 */
        const val SIMILAR_WARN_THRESHOLD = 0.85

        const val DUPLICATE_SAMPLE_LIMIT = 40
        val WHITESPACE = Regex("""\s+""")
    }
}
