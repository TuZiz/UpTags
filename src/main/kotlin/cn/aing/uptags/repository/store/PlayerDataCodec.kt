package cn.aing.uptags.repository.store

import cn.aing.uptags.model.config.CurrencyType
import cn.aing.uptags.model.runtime.CustomTitleData
import cn.aing.uptags.model.runtime.CustomTitleOrderStatus
import cn.aing.uptags.model.runtime.CustomTitlePurchaseOrderData
import cn.aing.uptags.model.runtime.PlayerTagData
import cn.aing.uptags.model.runtime.TagColorProfile
import cn.aing.uptags.model.runtime.TagProgress
import java.util.UUID

object PlayerDataCodec {
    private const val schemaVersion = "schema_version=2"

    fun serialize(data: PlayerTagData): String {
        val tagParts = data.tagProgress.entries.joinToString(";;") { (tagId, progress) ->
            listOf(
                encode(tagId),
                encode(progress.selectedParticleId ?: ""),
                progress.ownedParticles.joinToString(",") { encode(it) },
                progress.activeBuffs.joinToString(",") { encode(it) },
                progress.buffLevels.entries.joinToString(",") { "${encode(it.key)}:${it.value}" },
            ).joinToString("|")
        }
        val colorOverrideParts = data.tagColorOverrides.values.joinToString(";;") { override ->
            listOf(
                encode(override.tagId),
                override.palette.joinToString(",") { encode(it) },
                override.updatedAt.toString(),
            ).joinToString("|")
        }
        val customParts = data.customTitles.values.joinToString(";;") { custom ->
            listOf(
                encode(custom.id),
                encode(custom.rawText),
                encode(custom.presetId),
                custom.manualColors.joinToString(",") { encode(it) },
                custom.randomSchemes.joinToString("~~") { scheme -> scheme.joinToString(",") { encode(it) } },
                custom.selectedSchemeIndex.toString(),
                custom.createdAt.toString(),
                encode(custom.groupId ?: ""),
            ).joinToString("|")
        }
        val orderParts = data.customTitleOrders.values.joinToString(";;") { order ->
            listOf(
                encode(order.orderId),
                encode(order.titleId),
                encode(order.rawText),
                encode(order.presetId),
                encode(order.groupId ?: ""),
                encode(order.currencyType.name),
                order.currencyAmount.toString(),
                encode(order.status.name),
                order.createdAt.toString(),
                order.updatedAt.toString(),
                encode(order.failureReason ?: ""),
                encode(order.previousEquippedTagId ?: ""),
                encode(order.previousEquippedCustomTitleId ?: ""),
            ).joinToString("|")
        }
        return listOf(
            schemaVersion,
            data.ownedTags.joinToString(",") { encode(it) },
            encode(data.equippedTagId ?: ""),
            tagParts,
            data.titleCoinBalance.toString(),
            if (data.titleCoinInitialized) "1" else "0",
            customParts,
            encode(data.equippedCustomTitleId ?: ""),
            colorOverrideParts,
            orderParts,
        ).joinToString("###")
    }

    fun deserialize(uniqueId: UUID, raw: String): PlayerTagData {
        val data = PlayerTagData(uniqueId)
        val parts = raw.split("###")
        val offset = if (parts.firstOrNull() == schemaVersion) 1 else 0
        fun part(index: Int): String? = parts.getOrNull(index + offset)
        if (part(0)?.isNotBlank() == true) {
            data.ownedTags += part(0).orEmpty().split(',').filter { it.isNotBlank() }.map(::decode)
        }
        data.equippedTagId = part(1)?.ifBlank { null }?.let(::decode)
        part(2)
            ?.takeIf { it.isNotBlank() }
            ?.split(";;")
            ?.forEach { entry ->
                val entryParts = entry.split('|')
                val tagId = entryParts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::decode) ?: return@forEach
                val progress = TagProgress()
                progress.selectedParticleId = entryParts.getOrNull(1)?.ifBlank { null }?.let(::decode)
                entryParts.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(',')?.filter { it.isNotBlank() }?.map(::decode)?.let { progress.ownedParticles += it }
                entryParts.getOrNull(3)?.takeIf { it.isNotBlank() }?.split(',')?.filter { it.isNotBlank() }?.map(::decode)?.let { progress.activeBuffs += it }
                entryParts.getOrNull(4)?.takeIf { it.isNotBlank() }?.split(',')?.forEach { pair ->
                    val pieces = pair.split(':', limit = 2)
                    val buffId = pieces.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::decode) ?: return@forEach
                    val level = pieces.getOrNull(1)?.toIntOrNull() ?: 0
                    progress.buffLevels[buffId] = level
                }
                data.tagProgress[tagId] = progress
            }
        data.titleCoinBalance = part(3)?.toDoubleOrNull() ?: 0.0
        data.titleCoinInitialized = part(4) == "1"
        part(5)
            ?.takeIf { it.isNotBlank() }
            ?.split(";;")
            ?.forEach { entry ->
                val entryParts = entry.split('|')
                val id = entryParts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::decode) ?: return@forEach
                val custom = CustomTitleData(
                    id = id,
                    rawText = entryParts.getOrNull(1)?.let(::decode) ?: "",
                    presetId = entryParts.getOrNull(2)?.let(::decode) ?: "default",
                    groupId = entryParts.getOrNull(7)?.ifBlank { null }?.let(::decode),
                    manualColors = entryParts.getOrNull(3)?.takeIf { it.isNotBlank() }?.split(',')?.filter { it.isNotBlank() }?.map(::decode)?.toMutableList() ?: mutableListOf(),
                    randomSchemes = entryParts.getOrNull(4)?.takeIf { it.isNotBlank() }?.split("~~")?.map { scheme ->
                        scheme.split(',').filter { it.isNotBlank() }.map(::decode).toMutableList()
                    }?.toMutableList() ?: mutableListOf(),
                    selectedSchemeIndex = entryParts.getOrNull(5)?.toIntOrNull() ?: 0,
                    createdAt = entryParts.getOrNull(6)?.toLongOrNull() ?: System.currentTimeMillis(),
                )
                data.customTitles[id] = custom
            }
        data.equippedCustomTitleId = part(6)?.ifBlank { null }?.let(::decode)
        part(7)
            ?.takeIf { it.isNotBlank() }
            ?.split(";;")
            ?.forEach { entry ->
                val entryParts = entry.split('|')
                val tagId = entryParts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::decode) ?: return@forEach
                data.tagColorOverrides[tagId] = TagColorProfile(
                    tagId = tagId,
                    palette = entryParts.getOrNull(1)?.takeIf { it.isNotBlank() }?.split(',')?.filter { it.isNotBlank() }?.map(::decode)?.toMutableList() ?: mutableListOf(),
                    updatedAt = entryParts.getOrNull(2)?.toLongOrNull() ?: System.currentTimeMillis(),
                )
            }
        part(8)
            ?.takeIf { it.isNotBlank() }
            ?.split(";;")
            ?.forEach { entry ->
                val entryParts = entry.split('|')
                val orderId = entryParts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::decode) ?: return@forEach
                val titleId = entryParts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::decode) ?: return@forEach
                data.customTitleOrders[orderId] = CustomTitlePurchaseOrderData(
                    orderId = orderId,
                    titleId = titleId,
                    rawText = entryParts.getOrNull(2)?.let(::decode) ?: "",
                    presetId = entryParts.getOrNull(3)?.let(::decode) ?: "default",
                    groupId = entryParts.getOrNull(4)?.ifBlank { null }?.let(::decode),
                    currencyType = CurrencyType.from(entryParts.getOrNull(5)?.let(::decode)),
                    currencyAmount = entryParts.getOrNull(6)?.toDoubleOrNull() ?: 0.0,
                    status = CustomTitleOrderStatus.from(entryParts.getOrNull(7)?.let(::decode)),
                    createdAt = entryParts.getOrNull(8)?.toLongOrNull() ?: System.currentTimeMillis(),
                    updatedAt = entryParts.getOrNull(9)?.toLongOrNull() ?: System.currentTimeMillis(),
                    failureReason = entryParts.getOrNull(10)?.ifBlank { null }?.let(::decode),
                    previousEquippedTagId = entryParts.getOrNull(11)?.ifBlank { null }?.let(::decode),
                    previousEquippedCustomTitleId = entryParts.getOrNull(12)?.ifBlank { null }?.let(::decode),
                )
            }
        return data
    }

    private fun encode(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                ',' -> append("\\c")
                ':' -> append("\\d")
                ';' -> append("\\s")
                '#' -> append("\\h")
                '~' -> append("\\t")
                else -> append(char)
            }
        }
    }

    private fun decode(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val marker = value[index + 1]) {
                    '\\' -> result.append('\\')
                    'p' -> result.append('|')
                    'c' -> result.append(',')
                    'd' -> result.append(':')
                    's' -> result.append(';')
                    'h' -> result.append('#')
                    't' -> result.append('~')
                    else -> result.append(marker)
                }
                index += 2
                continue
            }
            result.append(char)
            index++
        }
        return result.toString()
    }
}
