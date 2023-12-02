package dev.reactant.reactant.extra.server

import dev.reactant.reactant.core.ReactantCore
import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.component.lifecycle.LifeCycleHook
import dev.reactant.reactant.core.dependency.injection.Provide
import dev.reactant.reactant.core.dependency.injection.producer.Provider
import dev.reactant.reactant.core.dependency.layers.SystemLevel
import dev.reactant.reactant.extra.profiler.PublishingProfilerDataProvider
import dev.reactant.reactant.service.spec.profiler.ProfilerDataProvider
import dev.reactant.reactant.service.spec.server.EventService
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.bukkit.Bukkit
import org.bukkit.event.*
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.reflect.KClass
import kotlin.reflect.KType


@Component
private class ReactantEventServiceProvider(
        private val profilerDataProvider: PublishingProfilerDataProvider = PublishingProfilerDataProvider()
) : LifeCycleHook, Listener, ProfilerDataProvider by profilerDataProvider, SystemLevel {
    /**
     * Map which using Pair<EventClass, Boolean> as key, while the Boolean is ignoreCancelled
     */
    private val eventPrioritySubjectMap = HashMap<Pair<Class<out Event>, Boolean>, EnumMap<EventPriority, PublishSubject<Event>>>();
    private val listeningEventClasses = HashSet<Class<out Event>>();

    override fun onDisable() {
        HandlerList.unregisterAll(this)
        eventPrioritySubjectMap.flatMap { it.value.map { it.value } }.map { it.onComplete() }
    }

    @Provide(".*", true)
    private fun getEventService(kType: KType, path: String, requester: Provider) = ReactantEventService(requester)

    private inner class ReactantEventService(override val requester: Provider) : EventService {

        private fun onEvent(eventClass: Class<out Event>, event: Event, ignoreCancelled: Boolean, priority: EventPriority) {
            if (eventPrioritySubjectMap.containsKey(eventClass to ignoreCancelled)
                    && eventPrioritySubjectMap[eventClass to ignoreCancelled]!!.containsKey(priority)) {
                eventPrioritySubjectMap[eventClass to ignoreCancelled]!![priority]!!.onNext(event)
            }
            if (eventClass.superclass != Event::class.java) {
                onEvent(eventClass.superclass as Class<out Event>, event, ignoreCancelled, priority)
            }
        }

        /**
         * Register the event listener if the event class has never be reigstered
         */
        private fun listen(eventClass: Class<out Event>) {
            if (!listeningEventClasses.contains(eventClass)) {
                listeningEventClasses.add(eventClass)
                EventPriority.values().forEach { priority ->
                    listOf(true, false).forEach { ignoreCancelled ->
                        Bukkit.getPluginManager().registerEvent(eventClass, this@ReactantEventServiceProvider, priority,
                                { _, event -> onEvent(event::class.java, event, ignoreCancelled, priority) }, ReactantCore.instance, ignoreCancelled)
                    }
                }
            }
        }

        override fun <T : Event> on(eventClass: KClass<T>, ignoreCancelled: Boolean, eventPriority: EventPriority): Observable<T> {
            listen(eventClass.java)

            var disposable: Disposable? = null

            return Observable.create<T> { source ->
                disposable = (eventPrioritySubjectMap
                        .getOrPut(eventClass.java to ignoreCancelled, { EnumMap(org.bukkit.event.EventPriority::class.java) })
                        .getOrPut(eventPriority, { PublishSubject.create() }))
                        .let {
                            if (ignoreCancelled) it.filter { e -> (e as? Cancellable)?.isCancelled?.not() ?: true }
                            else it
                        }
                        .doOnError { it.printStackTrace() }
                        .subscribe { event ->
                            profilerDataProvider.measure(listOf(eventClass.qualifiedName ?: "Unknown"), requester) {
                                source.onNext(event as T)
                            }
                        }
            }.doOnDispose { disposable!!.dispose() }
        }

        override fun pushEvent(event: Event) = Bukkit.getPluginManager().callEvent(event)
    }
}
