package dev.reactant.reactant.extra.server

import dev.reactant.reactant.core.ReactantCore
import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.dependency.injection.Provide
import dev.reactant.reactant.core.dependency.injection.producer.Provider
import dev.reactant.reactant.core.dependency.layers.SystemLevel
import dev.reactant.reactant.extra.profiler.PublishingProfilerDataProvider
import dev.reactant.reactant.service.spec.profiler.ProfilerDataProvider
import dev.reactant.reactant.service.spec.server.SchedulerService
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Completable.defer
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.schedulers.Schedulers
import org.bukkit.Bukkit
import org.bukkit.event.Listener
import kotlin.reflect.KType

@Component
private class ReactantSchedulerServiceProvider(
        private val profilerDataProvider: PublishingProfilerDataProvider = PublishingProfilerDataProvider()
) : Listener, ProfilerDataProvider by profilerDataProvider, SystemLevel {

    @Provide(".*", true)
    private fun getSchedulerService(kType: KType, path: String, requester: Provider) = ReactantSchedulerService(requester)

    private inner class ReactantSchedulerService(val requester: Provider) : SchedulerService {
        private val noSubscribeOnException
            get() = UnsupportedOperationException("Look like you are trying to use subscribeOn on the SchedulerService")

        override fun next(): Completable = defer {
            var taskId: Int? = null
            val currentThread = Thread.currentThread()
            Completable.create { source ->
                if (Thread.currentThread() != currentThread) throw noSubscribeOnException;
                taskId = Bukkit.getScheduler().runTask(ReactantCore.instance, Runnable {
                    profilerDataProvider.measure(listOf("next"), requester) {
                        source.onComplete()
                    }
                }).taskId
            }.doOnDispose { Bukkit.getScheduler().cancelTask(taskId!!) }
        }

        override fun timer(delay: Long): Completable = defer {
            var taskId: Int? = null
            val currentThread = Thread.currentThread()
            Completable.create { source ->
                if (Thread.currentThread() != currentThread) throw noSubscribeOnException;
                taskId = Bukkit.getScheduler().runTaskLater(ReactantCore.instance, Runnable {
                    profilerDataProvider.measure(listOf("timer"), requester) {
                        source.onComplete()
                    }
                }, delay).taskId
            }.doOnDispose { Bukkit.getScheduler().cancelTask(taskId!!) }
        }

        override fun interval(delay: Long, period: Long): Observable<Int> = Observable.defer {
            var taskId: Int? = null
            var count = 0
            val currentThread = Thread.currentThread()
            Observable.create<Int> { source ->
                if (Thread.currentThread() != currentThread) throw noSubscribeOnException;
                taskId = Bukkit.getScheduler()
                        .runTaskTimer(ReactantCore.instance, Runnable {
                            profilerDataProvider.measure(listOf("interval"), requester) {
                                source.onNext(count++)
                            }
                        }, delay, period).taskId
            }.doOnDispose { Bukkit.getScheduler().cancelTask(taskId!!) }
        }

        override val mainThreadScheduler: Scheduler = Schedulers.from { runnable: Runnable ->
            Bukkit.getServer().scheduler.runTask(ReactantCore.instance, Runnable {
                profilerDataProvider.measure(listOf("mainThreadScheduler"), requester) {
                    runnable.run()
                }
            })
        }
    }
}
