package dev.reactant.reactant.extra.i18n

import I18nTranslation
import dev.reactant.reactant.core.ReactantCore
import dev.reactant.reactant.core.component.Component
import dev.reactant.reactant.core.component.container.ContainerManager
import dev.reactant.reactant.core.component.lifecycle.LifeCycleHook
import dev.reactant.reactant.extra.parser.GsonJsonParserService
import dev.reactant.reactant.service.spec.config.ConfigService
import dev.reactant.reactant.service.spec.config.get
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import javassist.ClassClassPath
import javassist.ClassPool
import javassist.CtNewMethod
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.jvmErasure


typealias LanguageTableClass = KClass<out I18nTable>

@Component
class I18nService(
        private val configService: ConfigService,
        private val jsonParserService: GsonJsonParserService,
        private val containerManager: ContainerManager
) : LifeCycleHook {
    private val _tableClass: HashSet<LanguageTableClass> = HashSet()
    val tableClasses: Set<LanguageTableClass> get() = _tableClass

    private val defaultTable = ConcurrentHashMap<LanguageTableClass, Any>()

    private val cachedLanguageTables = ConcurrentHashMap<LanguageTableClass, HashMap<String, Any?>>()

    override fun onEnable() {

        // Load all I18nTable interfaces
        @Suppress("UNCHECKED_CAST")
        containerManager.containers
            .flatMap { it.getClassesByAnnotation(I18n::class) }
            .map { it as KClass<out I18nTable> }
                .filter {
                    if (it.isFinal) {
                        ReactantCore.logger.error("I18n Table must be a open parameterless concrete class: ${it}")
                        false
                    } else kotlin.runCatching {
                        defaultTable[it] = it.createInstance()
                    }.onFailure { e ->
                        ReactantCore.logger.error("I18n Table must be a open parameterless concrete class: ${it}")
                    }.isSuccess

                }
                .also { _tableClass.addAll(it) }
                .forEach { cachedLanguageTables[it] = HashMap() }
    }

    fun getLanguageFilePath(table: LanguageTableClass, languageCode: String): String {
        val languagePath = "${table.qualifiedName!!.replace('.', '/')}/$languageCode.json"
        return "${ReactantCore.configDirPath}/i18n/tables/$languagePath"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : I18nTable> findLanguage(tableClass: KClass<T>, languageCode: String): Maybe<T> {
        if (cachedLanguageTables[tableClass]!!.containsKey(languageCode))
            return Maybe.fromOptional(Optional.ofNullable(cachedLanguageTables[tableClass]!![languageCode]?.let { it as T }))
                    .subscribeOn(Schedulers.trampoline())

        return Maybe.defer {
            Maybe.fromOptional(Optional.ofNullable(cachedLanguageTables[tableClass]!![languageCode]?.let { it as T }))
        }.switchIfEmpty(Maybe.defer {
            configService.get<I18nTranslation>(jsonParserService, getLanguageFilePath(tableClass, languageCode))
                    .map { convertTranslationToLanguageTable(languageCode, it.content, tableClass) }
                    .switchIfEmpty(Maybe.defer {
                        // fallback
                        if (languageCode.contains("_"))
                            findLanguage(tableClass, languageCode.split("_")[0])
                        else Maybe.empty<T>()
                    })
                    .doOnSuccess { cachedLanguageTables[tableClass]!![languageCode] = it }
        })
    }

    private fun <T : I18nTable> convertTranslationToLanguageTable(languageCode: String, translation: I18nTranslation, target: KClass<T>): T {
        val classPool = ClassPool.getDefault()
        classPool.insertClassPath(ClassClassPath(target.java))
        val tableCtClass = classPool.get(target.java.canonicalName)
        val languageTableCtClass = classPool.makeClass(tableCtClass.name + "_" + languageCode, tableCtClass)

        target.declaredMemberFunctions.forEach { langFun ->
            if (translation.translations.containsKey(langFun.name)) {
                // Replace translation's placeholder as parameter
                var translationResult = "\"${translation.translations[langFun.name]!!.replace("\"", "\\\"")}\""
                langFun.parameters.drop(1)
                        .forEach { param -> translationResult = translationResult.replace("\$${param.name}", "\"+${param.name}+\"") }

                // Create method parameters
                val parameters = langFun.parameters.drop(1).map { "${it.type.jvmErasure.java.canonicalName} ${it.name}" }.joinToString(",")

                // Add method to the translation class
                CtNewMethod.make("public String ${langFun.name}($parameters) { return ${translationResult}; }",
                        languageTableCtClass).let { languageTableCtClass.addMethod(it) }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return languageTableCtClass.toClass(target.java.classLoader).newInstance() as T;
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : I18nTable> getLanguage(tableClass: KClass<T>, languageCodes: List<String>): Single<T> {
        if (!defaultTable.containsKey(tableClass))
            throw IllegalArgumentException("I18n table class not registered, ${tableClass.qualifiedName}. " +
                    "Make sure you table class is annotated with @I18n and it is a open parameterless concrete class")
        return Observable.fromIterable(languageCodes)
                .flatMapMaybe { findLanguage(tableClass, it) }
                .first(defaultTable[tableClass] as T)
    }

    inline fun <reified T : I18nTable> getLanguage(languageCodes: List<String>) = getLanguage(T::class, languageCodes)
}

