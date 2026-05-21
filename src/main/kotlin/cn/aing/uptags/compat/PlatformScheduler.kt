package cn.aing.uptags.compat

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

interface TaskHandle {
    fun cancel()
}

private class SimpleTaskHandle(private val canceller: () -> Unit) : TaskHandle {
    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            canceller()
        }
    }
}

class PlatformScheduler(private val plugin: JavaPlugin) {
    private val asyncExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${plugin.name}-io").apply { isDaemon = true }
    }
    private val foliaBridge = FoliaBridge.detect(plugin)

    fun isFolia(): Boolean = foliaBridge != null

    fun runAsync(task: () -> Unit): TaskHandle {
        val future = asyncExecutor.submit(wrap(task))
        return SimpleTaskHandle { future.cancel(false) }
    }

    fun runGlobal(task: () -> Unit): TaskHandle {
        val bridge = foliaBridge
        if (bridge != null) {
            return bridge.executeGlobal(wrap(task))
        }
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, wrap(task))
        return bukkitTask.asHandle()
    }

    fun runPlayer(player: Player, task: () -> Unit): TaskHandle {
        val bridge = foliaBridge
        if (bridge != null) {
            return bridge.executePlayer(player, wrap(task))
        }
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, wrap(task))
        return bukkitTask.asHandle()
    }

    fun runPlayerOrRetired(player: Player, retired: () -> Unit, task: () -> Unit): TaskHandle {
        val bridge = foliaBridge
        if (bridge != null) {
            return bridge.executePlayer(player, wrap(task), wrap(retired))
        }
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, wrap(task))
        return bukkitTask.asHandle()
    }

    fun runPlayerRepeating(player: Player, delayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle? {
        val bridge = foliaBridge
        if (bridge != null) {
            return bridge.runPlayerRepeating(player, delayTicks, periodTicks, wrap(task))
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, wrap(task), delayTicks, periodTicks).asHandle()
    }

    fun shutdown() {
        asyncExecutor.shutdownNow()
    }

    private fun BukkitTask.asHandle(): TaskHandle = SimpleTaskHandle { cancel() }

    private fun wrap(task: () -> Unit): Runnable = Runnable {
        try {
            task()
        } catch (ex: Throwable) {
            plugin.logger.severe("Scheduled task failed: ${ex.message}")
            ex.printStackTrace()
        }
    }

    private fun wrap(task: Runnable): Runnable = Runnable {
        try {
            task.run()
        } catch (ex: Throwable) {
            plugin.logger.severe("Scheduled task failed: ${ex.message}")
            ex.printStackTrace()
        }
    }
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
    fun executeGlobal(task: Runnable): TaskHandle {
        val scheduledTask = globalExecuteMethod.invoke(globalScheduler, plugin, task)
        return if (scheduledTask != null) {
            SimpleTaskHandle { scheduledTaskCancelMethod.invoke(scheduledTask) }
        } else {
            SimpleTaskHandle {}
        }
    }

    fun executePlayer(player: Player, task: Runnable): TaskHandle = executePlayer(player, task, Runnable {})

    fun executePlayer(player: Player, task: Runnable, retired: Runnable): TaskHandle {
        val scheduler = playerSchedulerMethod.invoke(player)
        val scheduledTask = entityExecuteMethod.invoke(scheduler, plugin, task, retired, 1L)
        return if (scheduledTask != null) {
            SimpleTaskHandle { scheduledTaskCancelMethod.invoke(scheduledTask) }
        } else {
            SimpleTaskHandle {}
        }
    }

    fun runPlayerRepeating(player: Player, delayTicks: Long, periodTicks: Long, task: Runnable): TaskHandle? {
        val scheduler = playerSchedulerMethod.invoke(player)
        val consumer = consumerProxy { task.run() }
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
