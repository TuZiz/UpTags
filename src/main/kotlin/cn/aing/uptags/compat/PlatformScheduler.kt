package cn.aing.uptags.compat

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

interface TaskHandle {
    fun cancel()
}

private class SimpleTaskHandle(private val canceller: () -> Unit) : TaskHandle {
    override fun cancel() = canceller()
}

class PlatformScheduler(private val plugin: JavaPlugin) {
    private val asyncExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${plugin.name}-io").apply { isDaemon = true }
    }
    private val foliaBridge = FoliaBridge.detect(plugin)

    fun isFolia(): Boolean = foliaBridge != null

    fun runAsync(task: () -> Unit): TaskHandle {
        val future = asyncExecutor.submit(task)
        return SimpleTaskHandle { future.cancel(false) }
    }

    fun runGlobal(task: () -> Unit): TaskHandle {
        val bridge = foliaBridge
        if (bridge != null) {
            bridge.executeGlobal(task)
            return SimpleTaskHandle {}
        }
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
        return bukkitTask.asHandle()
    }

    fun runPlayer(player: Player, task: () -> Unit): TaskHandle {
        val bridge = foliaBridge
        if (bridge != null) {
            bridge.executePlayer(player, task)
            return SimpleTaskHandle {}
        }
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
        return bukkitTask.asHandle()
    }

    fun runPlayerRepeating(player: Player, delayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle? {
        val bridge = foliaBridge
        if (bridge != null) {
            return bridge.runPlayerRepeating(player, delayTicks, periodTicks, task)
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks).asHandle()
    }

    fun shutdown() {
        asyncExecutor.shutdownNow()
    }

    private fun BukkitTask.asHandle(): TaskHandle = SimpleTaskHandle { cancel() }
}

private class FoliaBridge private constructor(
    private val plugin: JavaPlugin,
    private val globalScheduler: Any,
    private val globalExecuteMethod: java.lang.reflect.Method,
    private val playerSchedulerMethod: java.lang.reflect.Method,
    private val entityExecuteMethod: java.lang.reflect.Method,
    private val entityRunAtFixedRateMethod: java.lang.reflect.Method,
    private val scheduledTaskCancelMethod: java.lang.reflect.Method,
) {
    fun executeGlobal(task: () -> Unit) {
        globalExecuteMethod.invoke(globalScheduler, plugin, Runnable(task))
    }

    fun executePlayer(player: Player, task: () -> Unit) {
        val scheduler = playerSchedulerMethod.invoke(player)
        entityExecuteMethod.invoke(scheduler, plugin, Runnable(task), Runnable {}, 1L)
    }

    fun runPlayerRepeating(player: Player, delayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle? {
        val scheduler = playerSchedulerMethod.invoke(player)
        val consumer = consumerProxy { task() }
        val scheduledTask = entityRunAtFixedRateMethod.invoke(scheduler, plugin, consumer, Runnable {}, delayTicks, periodTicks)
            ?: return null
        return SimpleTaskHandle { scheduledTaskCancelMethod.invoke(scheduledTask) }
    }

    private fun consumerProxy(block: () -> Unit): Any {
        val consumerClass = Consumer::class.java
        return Proxy.newProxyInstance(consumerClass.classLoader, arrayOf(consumerClass)) { _, method, _ ->
            if (method.name == "accept") {
                block()
            }
            null
        }
    }

    companion object {
        fun detect(plugin: JavaPlugin): FoliaBridge? {
            return try {
                val serverClass = Bukkit.getServer()::class.java
                val globalSchedulerMethod = serverClass.getMethod("getGlobalRegionScheduler")
                val globalScheduler = globalSchedulerMethod.invoke(Bukkit.getServer())
                val globalSchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
                val entitySchedulerClass = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
                val scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask")
                val globalExecuteMethod = globalSchedulerClass.getMethod("execute", org.bukkit.plugin.Plugin::class.java, Runnable::class.java)
                val playerSchedulerMethod = Player::class.java.getMethod("getScheduler")
                val entityExecuteMethod = entitySchedulerClass.getMethod(
                    "execute",
                    org.bukkit.plugin.Plugin::class.java,
                    Runnable::class.java,
                    Runnable::class.java,
                    java.lang.Long.TYPE,
                )
                val entityRunAtFixedRateMethod = entitySchedulerClass.getMethod(
                    "runAtFixedRate",
                    org.bukkit.plugin.Plugin::class.java,
                    Consumer::class.java,
                    Runnable::class.java,
                    java.lang.Long.TYPE,
                    java.lang.Long.TYPE,
                )
                val scheduledTaskCancelMethod = scheduledTaskClass.getMethod("cancel")
                FoliaBridge(
                    plugin,
                    globalScheduler,
                    globalExecuteMethod,
                    playerSchedulerMethod,
                    entityExecuteMethod,
                    entityRunAtFixedRateMethod,
                    scheduledTaskCancelMethod,
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
