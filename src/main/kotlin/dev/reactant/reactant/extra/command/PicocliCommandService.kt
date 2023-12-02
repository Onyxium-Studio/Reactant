package dev.reactant.reactant.extra.command

import dev.reactant.reactant.core.ReactantCore
import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.component.lifecycle.LifeCycleHook
import dev.reactant.reactant.core.component.lifecycle.LifeCycleInspector
import dev.reactant.reactant.core.dependency.injection.Provide
import dev.reactant.reactant.core.dependency.injection.producer.ComponentProvider
import dev.reactant.reactant.core.dependency.injection.producer.Provider
import dev.reactant.reactant.extra.profiler.PublishingProfilerDataProvider
import dev.reactant.reactant.service.spec.dsl.Registrable
import dev.reactant.reactant.service.spec.profiler.ProfilerDataProvider
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.SimpleCommandMap
import picocli.AutoComplete
import picocli.CommandLine.Model
import kotlin.reflect.KType

@Component
class PicocliCommandServiceProvider(
    private val profilerDataProvider: PublishingProfilerDataProvider = PublishingProfilerDataProvider()
) : LifeCycleHook, LifeCycleInspector, ProfilerDataProvider by profilerDataProvider {

    private val commandTreeMap = HashMap<String, CommandTree>()

    private val registerCommandNameMap = HashMap<Any, HashSet<String>>()
    private lateinit var bukkitCommandMap: SimpleCommandMap
    override fun onEnable() {
        Bukkit.getServer()::class.java.getDeclaredField("commandMap").apply {
            isAccessible = true
            bukkitCommandMap = get(Bukkit.getServer()) as SimpleCommandMap
        }
    }

    override fun beforeDisable(componentProvider: ComponentProvider<Any>) {
        registerCommandNameMap[componentProvider]?.forEach(this::unregisterCommand)
        registerCommandNameMap.remove(componentProvider)
    }

    fun unregisterCommand(commandName: String) {
        if (commandTreeMap.containsKey(commandName)) {
            commandTreeMap.remove(commandName)
            try {
                bukkitCommandMap::class.java.getDeclaredField("knownCommands").apply {
                    isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (get(bukkitCommandMap) as HashMap<String, org.bukkit.command.Command>)
                        .remove(commandName)
                }
            } catch (e: NoSuchFieldException) {
            }
        } else {
            throw IllegalArgumentException()
        }
    }

    @Provide(".*", true)
    private fun getPicocliCommandService(kType: KType, path: String, requester: Provider) = PicocliCommandServiceImpl(requester)

    inner class PicocliCommandServiceImpl(val requester: Provider) : PicocliCommandService {
        override fun registerCommand(commandRunnableProvider: () -> ReactantCommand): CommandTree {
            val reactantCommand = commandRunnableProvider()
            val commandSpec = Model.CommandSpec.forAnnotatedObject(reactantCommand)
            var name = commandSpec.name()
            name = reviseConflictCommand(name)

            registerCommandNameMap.getOrPut(requester) { hashSetOf() }.add(name)
            commandTreeMap[name] = CommandTree(name, commandRunnableProvider)
            Bukkit.getHelpMap().addTopic(reactantCommand.getHelpTopic())

            val bukkitCommand = PicocliBukkitCommand(commandSpec, profilerDataProvider, requester, commandTreeMap[name]!!)
            bukkitCommandMap.register(requester.productType.javaClass.canonicalName, bukkitCommand)

            Bukkit.getServer()::class.java.getDeclaredMethod("syncCommands").let {
                it.isAccessible = true
                it.invoke(Bukkit.getServer())
            }

            return commandTreeMap[name]!!
        }

        /**
         * Revise the command with "command$number" if it have a same name with other command
         */
        private fun reviseConflictCommand(name: String): String {
            var result = name
            val existingCommand = bukkitCommandMap.commands.map { it.name }
            if (existingCommand.contains(result)) {
                val revisedNumber = (2..Int.MAX_VALUE).first { !existingCommand.contains("$result$it") }
                result = "$result$revisedNumber"
                ReactantCore.logger.warn(
                    "Command result conflict: $result " +
                        "(register by ${requester.productType}), " +
                        "revised to $result"
                )
            }
            return result
        }

// DSL

        inner class CommandRegistering : PicocliCommandService.CommandRegistering {
            override fun command(commandProvider: () -> ReactantCommand, subRegistering: (PicocliCommandService.CommandRegistering.() -> Unit)?) {
                val commandTree: CommandTree = registerCommand(commandProvider)
                subRegistering?.let { SubCommandRegistering(commandTree, commandProvider).it() }
            }
        }

        inner class SubCommandRegistering(private val commandTree: CommandTree, private val rootCommandProvider: () -> Runnable) : PicocliCommandService.CommandRegistering {
            override fun command(commandProvider: () -> ReactantCommand, subRegistering: (PicocliCommandService.CommandRegistering.() -> Unit)?) {
                commandTree.addSubcommand(rootCommandProvider, commandProvider)
                subRegistering?.let { SubCommandRegistering(commandTree, commandProvider).it() }
            }
        }

        override operator fun invoke(registering: PicocliCommandService.CommandRegistering.() -> Unit) {
            CommandRegistering().apply(registering)
        }
    }
}

interface PicocliCommandService : Registrable<PicocliCommandService.CommandRegistering> {
    fun registerCommand(commandRunnableProvider: () -> ReactantCommand): CommandTree

    interface CommandRegistering {
        fun command(commandProvider: () -> ReactantCommand, subRegistering: (CommandRegistering.() -> Unit)? = null)
    }

    override fun registerBy(componentRegistrant: Any, registering: CommandRegistering.() -> Unit) = invoke(registering)

    operator fun invoke(registering: CommandRegistering.() -> Unit)
}

private val argsGroupingRegex = Regex("(\"(?:\\\\\"|[^\"])+\")|((?:\\\\\"|\\\\ |[^\" ])+)")

class PicocliBukkitCommand(
    val commandSpec: Model.CommandSpec,
    private val profilerDataProvider: PublishingProfilerDataProvider,
    private val requester: Provider,
    private val commandTree: CommandTree
) : org.bukkit.command.Command(
    commandSpec.name(),
    commandSpec.usageMessage().description().joinToString(" "),
    commandSpec.usageMessage().customSynopsis().joinToString(""),
    commandSpec.aliases().toList()
) {
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        val writer = CommandSenderWriter(sender)
        val grouppedArgs: List<String> = argsGroupingRegex.findAll(args.joinToString(" ")).map { it.value }.toList()
        val commandLine = commandTree.getCommandLine(sender, writer)
        profilerDataProvider.measure(grouppedArgs, requester) {
            val reactantCommand = commandLine.commandSpec.userObject() as ReactantCommand
            commandLine.setExecutionExceptionHandler { ex, _, _ -> reactantCommand.handleExecutionExceptions(ex, args) }
            commandLine.execute(*(grouppedArgs.toTypedArray()))
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        val candidates = arrayListOf<String>()
        val reactantCommand = commandTree.getDummyCommandLine().commandSpec.userObject() as ReactantCommand
        if (reactantCommand.helpTopicPermission != null && !sender.hasPermission(reactantCommand.helpTopicPermission)) {
            return candidates
        }

        AutoComplete.complete(
            commandTree.getDummyCommandLine().commandSpec, args, args.size - 1,
            args.lastOrNull()?.length
                ?: 0,
            0, candidates as List<String>
        )
        val result = candidates.map { (args.lastOrNull() ?: "") + it }.toMutableList()
        return result
    }
}
