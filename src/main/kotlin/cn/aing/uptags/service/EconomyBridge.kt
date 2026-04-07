package cn.aing.uptags.service

import cn.aing.uptags.model.config.CurrencyType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.lang.reflect.Method
import kotlin.math.ceil

class EconomyBridge(private val plugin: JavaPlugin) {
    private var vaultEconomy: Any? = null
    private var playerPointsApi: Any? = null
    private var titleCoinAccessor: ((Player) -> Double)? = null
    private var titleCoinWithdrawer: ((Player, Double) -> Boolean)? = null
    private var titleCoinDepositor: ((Player, Double) -> Boolean)? = null

    fun hook() {
        hookVault()
        hookPlayerPoints()
    }

    fun attachTitleCoinAccessors(
        balanceAccessor: (Player) -> Double,
        withdrawAccessor: (Player, Double) -> Boolean,
        depositAccessor: (Player, Double) -> Boolean,
    ) {
        titleCoinAccessor = balanceAccessor
        titleCoinWithdrawer = withdrawAccessor
        titleCoinDepositor = depositAccessor
    }

    fun isAvailable(type: CurrencyType): Boolean = when (type) {
        CurrencyType.MONEY -> vaultEconomy != null
        CurrencyType.POINTS -> playerPointsApi != null
        CurrencyType.TITLE_COIN -> titleCoinAccessor != null && titleCoinWithdrawer != null && titleCoinDepositor != null
    }

    fun displayName(type: CurrencyType): String = when (type) {
        CurrencyType.MONEY -> "金币"
        CurrencyType.POINTS -> "点券"
        CurrencyType.TITLE_COIN -> "称号币"
    }

    fun balance(player: Player, type: CurrencyType): Double = when (type) {
        CurrencyType.MONEY -> getVaultBalance(player)
        CurrencyType.POINTS -> getPlayerPoints(player)
        CurrencyType.TITLE_COIN -> titleCoinAccessor?.invoke(player) ?: 0.0
    }

    fun withdraw(player: Player, type: CurrencyType, amount: Double): Boolean = when (type) {
        CurrencyType.MONEY -> takeVault(player, amount)
        CurrencyType.POINTS -> takePlayerPoints(player, ceil(amount).toInt())
        CurrencyType.TITLE_COIN -> titleCoinWithdrawer?.invoke(player, amount) ?: false
    }

    fun deposit(player: Player, type: CurrencyType, amount: Double): Boolean = when (type) {
        CurrencyType.MONEY -> false
        CurrencyType.POINTS -> false
        CurrencyType.TITLE_COIN -> titleCoinDepositor?.invoke(player, amount) ?: false
    }

    private fun hookVault() {
        val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: run {
            vaultEconomy = null
            return
        }
        try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val registration = Bukkit.getServicesManager().getRegistration(economyClass)
            if (registration != null) {
                val providerMethod = registration.javaClass.getMethod("getProvider")
                vaultEconomy = providerMethod.invoke(registration)
            }
        } catch (_: Exception) {
            vaultEconomy = null
        }
    }

    private fun hookPlayerPoints() {
        val playerPoints = Bukkit.getPluginManager().getPlugin("PlayerPoints") ?: run {
            playerPointsApi = null
            return
        }
        try {
            val getApi = playerPoints.javaClass.getMethod("getAPI")
            playerPointsApi = getApi.invoke(playerPoints)
        } catch (_: Exception) {
            playerPointsApi = null
        }
    }

    private fun getVaultBalance(player: Player): Double {
        val economy = vaultEconomy ?: return 0.0
        return try {
            val method = economy.javaClass.getMethod("getBalance", org.bukkit.OfflinePlayer::class.java)
            (method.invoke(economy, player) as? Number)?.toDouble() ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun takeVault(player: Player, amount: Double): Boolean {
        val economy = vaultEconomy ?: return false
        return try {
            val has = economy.javaClass.getMethod("has", org.bukkit.OfflinePlayer::class.java, Double::class.javaPrimitiveType)
            if (!(has.invoke(economy, player, amount) as Boolean)) {
                return false
            }
            val withdraw = economy.javaClass.getMethod("withdrawPlayer", org.bukkit.OfflinePlayer::class.java, Double::class.javaPrimitiveType)
            val response = withdraw.invoke(economy, player, amount)
            val success = response.javaClass.getMethod("transactionSuccess")
            success.invoke(response) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun getPlayerPoints(player: Player): Double {
        val api = playerPointsApi ?: return 0.0
        return invokeNumber(api, "look", player.uniqueId) ?: invokeNumber(api, "look", player) ?: 0.0
    }

    private fun takePlayerPoints(player: Player, amount: Int): Boolean {
        val api = playerPointsApi ?: return false
        if (getPlayerPoints(player) < amount) {
            return false
        }
        return invokeBoolean(api, "take", player.uniqueId, amount) ?: invokeBoolean(api, "take", player, amount) ?: false
    }

    private fun invokeNumber(target: Any, methodName: String, vararg args: Any): Double? {
        return try {
            val method: Method = target.javaClass.methods.first { candidate ->
                candidate.name == methodName && candidate.parameterTypes.size == args.size
            }
            (method.invoke(target, *args) as? Number)?.toDouble()
        } catch (_: Exception) {
            null
        }
    }

    private fun invokeBoolean(target: Any, methodName: String, vararg args: Any): Boolean? {
        return try {
            val method: Method = target.javaClass.methods.first { candidate ->
                candidate.name == methodName && candidate.parameterTypes.size == args.size
            }
            val result = method.invoke(target, *args)
            if (result is Boolean) result else true
        } catch (_: Exception) {
            null
        }
    }
}
