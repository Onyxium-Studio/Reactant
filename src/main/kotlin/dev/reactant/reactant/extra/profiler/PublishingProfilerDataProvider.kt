package dev.reactant.reactant.extra.profiler

import dev.reactant.reactant.core.dependency.injection.producer.Provider
import dev.reactant.reactant.service.spec.profiler.ProfilerDataProvider
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlin.system.measureNanoTime

class PublishingProfilerDataProvider() : ProfilerDataProvider {
    private val profilerDataSubject: PublishSubject<ProfilerDataProvider.ProfilerData> = PublishSubject.create()
    override val profilerDataObservable: Observable<ProfilerDataProvider.ProfilerData> = profilerDataSubject.hide()

    fun <T> measure(path: List<String>, target: String, action: () -> T): T {
        if (!profilerDataSubject.hasObservers()) return action()
        var result: T? = null
        measureNanoTime { result = action() }
                .let { time -> profilerDataSubject.onNext(ProfilerDataProvider.ProfilerData(path, target, time)) }
        return result!!;
    }

    fun <T> measure(path: List<String>, target: Provider, action: () -> T): T = measure(path, target.productType.toString(), action)
}
