package cn.aing.uptags.service.economy

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
        CurrencyType.MONEY -> ensureVaultHooked() != null
        CurrencyType.POINTS -> ensurePlayerPointsHooked() != null
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

    fun refund(player: Player, type: CurrencyType, amount: Double): Boolean {
        if (amount <= 0.0) return true
        return when (type) {
            CurrencyType.TITLE_COIN -> titleCoinDepositor?.invoke(player, amount) == true
            CurrencyType.MONEY -> depositVault(player, amount)
            CurrencyType.POINTS -> givePlayerPoints(player, ceil(amount).toInt())
        }
    }

    private fun hookVault() {
        vaultEconomy = resolveVaultEconomy()
    }

    private fun hookPlayerPoints() {
        playerPointsApi = resolvePlayerPointsApi()
    }

    private fun getVaultBalance(player: Player): Double {
        val economy = ensureVaultHooked() ?: return 0.0
        return try {
            invokeNumber(economy, "getBalance", player)
                ?: invokeNumber(economy, "getBalance", player.name)
                ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun takeVault(player: Player, amount: Double): Boolean {
        val economy = ensureVaultHooked() ?: return false
        return try {
            val hasEnough = invokeBoolean(economy, "has", player, amount)
                ?: invokeBoolean(economy, "has", player.name, amount)
                ?: false
            if (!hasEnough) {
                return false
            }
            val response = invokeObject(economy, "withdrawPlayer", player, amount)
                ?: invokeObject(economy, "withdrawPlayer", player.name, amount)
                ?: return false
            val success = response.javaClass.getMethod("transactionSuccess")
            success.invoke(response) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun depositVault(player: Player, amount: Double): Boolean {
        val economy = ensureVaultHooked() ?: return false
        return try {
            val response = invokeObject(economy, "depositPlayer", player, amount)
                ?: invokeObject(economy, "depositPlayer", player.name, amount)
                ?: return false
            val success = response.javaClass.getMethod("transactionSuccess")
            success.invoke(response) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    private fun getPlayerPoints(player: Player): Double {
        val api = ensurePlayerPointsHooked() ?: return 0.0
        return invokeNumber(api, "look", player.uniqueId) ?: invokeNumber(api, "look", player) ?: 0.0
    }

    private fun takePlayerPoints(player: Player, amount: Int): Boolean {
        val api = ensurePlayerPointsHooked() ?: return false
        if (getPlayerPoints(player) < amount) {
            return false
        }
        return invokeBoolean(api, "take", player.uniqueId, amount)
            ?: invokeBoolean(api, "take", player, amount)
            ?: false
    }

    private fun givePlayerPoints(player: Player, amount: Int): Boolean {
        val api = ensurePlayerPointsHooked() ?: return false
        return invokeBoolean(api, "give", player.uniqueId, amount)
            ?: invokeBoolean(api, "give", player, amount)
            ?: false
    }

    private fun ensureVaultHooked(): Any? {
        val current = vaultEconomy
        if (current != null) {
            return current
        }
        vaultEconomy = resolveVaultEconomy()
        return vaultEconomy
    }

    private fun ensurePlayerPointsHooked(): Any? {
        val current = playerPointsApi
        if (current != null) {
            return current
        }
        playerPointsApi = resolvePlayerPointsApi()
        return playerPointsApi
    }

    private fun resolveVaultEconomy(): Any? {
        val vault = Bukkit.getPluginManager().getPlugin("Vault")
        if (vault == null || !vault.isEnabled) {
            return null
        }
        return try {
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val servicesManager = Bukkit.getServicesManager()
            val loadMethod = servicesManager.javaClass.getMethod("load", Class::class.java)
            val provider = loadMethod.invoke(servicesManager, economyClass)
            if (provider != null) {
                provider
            } else {
                val registration = servicesManager.javaClass.getMethod("getRegistration", Class::class.java)
                    .invoke(servicesManager, economyClass)
                    ?: return null
                registration.javaClass.getMethod("getProvider").invoke(registration)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePlayerPointsApi(): Any? {
        val playerPoints = Bukkit.getPluginManager().getPlugin("PlayerPoints")
        if (playerPoints == null || !playerPoints.isEnabled) {
            return null
        }
        return try {
            val getApi = playerPoints.javaClass.getMethod("getAPI")
            getApi.invoke(playerPoints)
        } catch (_: Exception) {
            null
        }
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

    private fun invokeObject(target: Any, methodName: String, vararg args: Any): Any? {
        return try {
            val method: Method = target.javaClass.methods.first { candidate ->
                candidate.name == methodName && candidate.parameterTypes.size == args.size
            }
            method.invoke(target, *args)
        } catch (_: Exception) {
            null
        }
    }
}
