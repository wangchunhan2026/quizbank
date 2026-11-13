package com.daily.quizbank.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 选项列表以 JSON 文本入库。
 * 之所以不用 "\n" 拼接，是因为选项正文本身可能含换行，分隔符方案会静默损坏数据。
 */
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}
