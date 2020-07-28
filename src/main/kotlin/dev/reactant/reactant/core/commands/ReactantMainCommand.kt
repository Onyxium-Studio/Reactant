package dev.reactant.reactant.core.commands

import dev.reactant.reactant.extra.command.ReactantCommand
import picocli.CommandLine

@CommandLine.Command(
        name = "reactant",
        aliases = ["react", "rea"],
        mixinStandardHelpOptions = true,
        description = ["Reactant commands"]
)
internal class ReactantMainCommand : ReactantCommand() {
    override fun execute() {
        showUsage()
    }
}
