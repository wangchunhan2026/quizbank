package com.daily.quizbank.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BankDao {

    @Query(
        """
        SELECT b.*, (SELECT COUNT(*) FROM questions q WHERE q.bankId = b.id) AS question_count
        FROM banks b
        ORDER BY b.updatedAt DESC
        """
    )
    fun observeAllWithCount(): Flow<List<BankWithCount>>

    @Query("SELECT * FROM banks WHERE id = :id")
    fun observeById(id: Long): Flow<BankEntity?>

    @Query("SELECT * FROM banks WHERE id = :id")
    suspend fun byId(id: Long): BankEntity?

    @Query("SELECT * FROM banks ORDER BY updatedAt DESC")
    suspend fun all(): List<BankEntity>

    @Query("SELECT * FROM banks WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): BankEntity?

    @Insert
    suspend fun insert(bank: BankEntity): Long

    @Update
    suspend fun update(bank: BankEntity)

    @Query("UPDATE banks SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())

    @Query("DELETE FROM banks WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface QuestionDao {

    /**
     * 题库详情页的题目列表：**新导入的在上，旧的往下**。
     *
     * 排序是 `createdAt DESC, id ASC` 而不是单纯 `id DESC`：
     * 同一次导入插进来的几百条 createdAt 完全相同，那时按 id 升序 = 文件里的原始顺序，
     * 一批之内不会被倒过来；不同批次之间按 createdAt 从新到旧。
     * 收录进来的题每条各有一个 createdAt，所以也是「最近收的在最上面」。
     *
     * **注意别把这条改到匹配那边去**：`byBank` / `all` / `stems` 必须保持 id 升序 ——
     * 拍题匹配遇到题库里的重复题时，取的是**先入库**那条（见 QuestionRepository.matcher）。
     */
    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY createdAt DESC, id ASC")
    fun observeByBank(bankId: Long): Flow<List<QuestionEntity>>

    @Query(
        """
        SELECT * FROM questions
        WHERE bankId = :bankId
          AND (stem LIKE '%' || :keyword || '%'
               OR answer LIKE '%' || :keyword || '%'
               OR analysis LIKE '%' || :keyword || '%'
               OR tags LIKE '%' || :keyword || '%')
        ORDER BY createdAt DESC, id ASC
        """
    )
    fun searchInBank(bankId: Long, keyword: String): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions WHERE bankId = :bankId ORDER BY id ASC")
    suspend fun byBank(bankId: Long): List<QuestionEntity>

    @Query("SELECT * FROM questions ORDER BY id ASC")
    suspend fun all(): List<QuestionEntity>

    @Query(
        """
        SELECT * FROM questions
        WHERE stem LIKE '%' || :keyword || '%'
           OR answer LIKE '%' || :keyword || '%'
           OR tags LIKE '%' || :keyword || '%'
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun searchAll(keyword: String, limit: Int): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :id")
    suspend fun byId(id: Long): QuestionEntity?

    /**
     * 拍题匹配用的题干投影。
     *
     * 只取三列而不是 SELECT *：几千道题的选项和解析加起来是好几个 MB，
     * 而匹配只看题干，没必要把它们全读进内存。
     */
    @Query("SELECT id, bankId, stem FROM questions")
    suspend fun stems(): List<StemRow>

    @Query("SELECT * FROM questions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<QuestionEntity>

    @Query("SELECT COUNT(*) FROM questions WHERE bankId = :bankId AND stem = :stem LIMIT 1")
    suspend fun countByStem(bankId: Long, stem: String): Int

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun totalCount(): Int

    @Insert
    suspend fun insert(item: QuestionEntity): Long

    @Insert
    suspend fun insertAll(items: List<QuestionEntity>): List<Long>

    @Update
    suspend fun update(item: QuestionEntity)

    @Delete
    suspend fun delete(item: QuestionEntity)

    @Query("UPDATE questions SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    /**
     * 合并题库时把一批题挪到另一个库。
     *
     * 走一条 UPDATE 而不是逐条 [update]：题库合并动辄搬上千条，
     * 每条一个事务在手机上要跑到十几秒。调用方负责分块（SQLite 一条语句最多 999 个绑定变量）。
     */
    @Query("UPDATE questions SET bankId = :bankId WHERE id IN (:ids)")
    suspend fun moveToBank(ids: List<Long>, bankId: Long)

    @Query("DELETE FROM questions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM questions WHERE bankId = :bankId")
    suspend fun deleteByBank(bankId: Long)
}

@Dao
interface UsageDao {

    @Insert
    suspend fun insert(record: UsageRecordEntity): Long

    @Query("SELECT * FROM usage_records ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<UsageRecordEntity>>

    @Query(
        """
        SELECT COUNT(*) AS calls,
               IFNULL(SUM(promptTokens), 0) AS promptTokens,
               IFNULL(SUM(completionTokens), 0) AS completionTokens,
               IFNULL(SUM(costMicros), 0) AS costMicros
        FROM usage_records
        """
    )
    fun observeSummary(): Flow<UsageSummary>

    @Query("DELETE FROM usage_records")
    suspend fun clear()
}
