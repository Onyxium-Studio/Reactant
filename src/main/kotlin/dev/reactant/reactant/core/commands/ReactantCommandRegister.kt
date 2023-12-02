package dev.reactant.reactant.core.commands

import dev.reactant.reactant.core.commands.component.ReactantComponentCommand
import dev.reactant.reactant.core.commands.component.ReactantComponentListSubCommand
import dev.reactant.reactant.core.commands.provider.ReactantProviderCommand
import dev.reactant.reactant.core.commands.provider.ReactantProviderListSubCommand
import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.component.container.ContainerManager
import dev.reactant.reactant.core.component.lifecycle.LifeCycleHook
import dev.reactant.reactant.core.dependency.ProviderManager
import dev.reactant.reactant.extra.command.PicocliCommandService
import dev.reactant.reactant.extra.file.FileIOUploadService
import dev.reactant.reactant.extra.parser.GsonJsonParserService
import dev.reactant.reactant.extra.profiler.ReactantProfilerService
import dev.reactant.reactant.extra.profiler.commands.ProfilerCommand
import dev.reactant.reactant.extra.profiler.commands.ProfilerListCommand
import dev.reactant.reactant.extra.profiler.commands.ProfilerStartCommand
import dev.reactant.reactant.extra.profiler.commands.ProfilerStopCommand
import dev.reactant.reactant.service.spec.config.ConfigService
import dev.reactant.reactant.service.spec.server.SchedulerService

@Component
internal class ReactantCommandRegister(
        private val commandService: PicocliCommandService,
        private val providerManager: ProviderManager,
        private val containerManager: ContainerManager,
        private val profilerService: ReactantProfilerService,
        private val fileIOUploadService: FileIOUploadService,
        private val jsonParserService: GsonJsonParserService,
        private val configService: ConfigService
) : LifeCycleHook {

    override fun onEnable() {
        commandService {
            command(::ReactantMainCommand) {

                command(::ReactantComponentCommand) {
                    command({ ReactantComponentListSubCommand(providerManager, containerManager, jsonParserService, fileIOUploadService) })
                }

                command(::ReactantEchoCommand)

                command(::ReactantProviderCommand) {
                    command({ ReactantProviderListSubCommand(providerManager, containerManager) })
                }

                command(::ProfilerCommand) {
                    command({ ProfilerListCommand(profilerService) })
                    command({ ProfilerStartCommand(profilerService, fileIOUploadService, jsonParserService) })
                    command({ ProfilerStopCommand(profilerService) })
                }
            }
        }
    }
}
