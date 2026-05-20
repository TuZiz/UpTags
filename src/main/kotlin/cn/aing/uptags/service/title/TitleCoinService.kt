package cn.aing.uptags.service.title

import cn.aing.uptags.config.ConfigRegistry
import cn.aing.uptags.repository.PlayerDataRepository
import org.bukkit.entity.Player
import java.util.UUID

internal class TitleCoinService(
    private val config: ConfigRegistry,
    private val repository: PlayerDataRepository,
) {
    fun add(player: Player, amount: Double): Double = add(player.uniqueId, amount)

    fun add(uniqueId: UUID, amount: Double): Double {
        val data = repository.get(uniqueId)
        data.titleCoinBalance += amount.coerceAtLeast(0.0)
        data.titleCoinInitialized = true
        repository.saveAsync(data)
        return data.titleCoinBalance
    }

    fun take(player: Player, amount: Double): Boolean = take(player.uniqueId, amount) != null

    fun take(uniqueId: UUID, amount: Double): Double? {
        val normalized = amount.coerceAtLeast(0.0)
        val data = repository.get(uniqueId)
        if (data.titleCoinBalance < normalized) return null
        data.titleCoinBalance -= normalized
        data.titleCoinInitialized = true
        repository.saveAsync(data)
        return data.titleCoinBalance
    }

    fun set(uniqueId: UUID, amount: Double): Double {
        val data = repository.get(uniqueId)
        data.titleCoinBalance = amount.coerceAtLeast(0.0)
        data.titleCoinInitialized = true
        repository.saveAsync(data)
        return data.titleCoinBalance
    }

    fun balance(player: Player): Double = repository.get(player.uniqueId).titleCoinBalance

    fun preparePlayer(player: Player) {
        val data = repository.get(player.uniqueId)
        if (!data.titleCoinInitialized && config.customTitleSettings.defaultTitleCoinBalance > 0.0) {
            data.titleCoinBalance = config.customTitleSettings.defaultTitleCoinBalance
            data.titleCoinInitialized = true
            repository.saveAsync(data)
        }
    }
}
