package net.swamphut.swampium.extra.db

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import net.swamphut.swampium.core.Swampium
import net.swamphut.swampium.core.swobject.container.SwObject
import net.swamphut.swampium.core.swobject.lifecycle.LifeCycleHook
import net.swamphut.swampium.service.spec.db.JdbcService
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

@SwObject
class SimpleJdbcService : LifeCycleHook, JdbcService {

    private val connectionsMap = HashMap<ConnectionInfo, Connection>();

    class ServiceConnectionImpl(private val connection: Connection) : JdbcService.ServiceConnection {
        override fun close(): Completable = Completable.defer { connection.close();Completable.complete(); }
                .subscribeOn(Schedulers.io())

        override val isClosed: Boolean get() = connection.isClosed

        override fun commit(): Completable = Completable.fromCallable { connection.commit() }

        override fun prepareStatement(statement: String): JdbcService.ServicePreparedStatement =
                ServicePreparedStatementImpl(connection.prepareStatement(statement), this)
    }

    class ServicePreparedStatementImpl(private val preparedStatement: PreparedStatement, override val connection: JdbcService.ServiceConnection) : JdbcService.ServicePreparedStatement {

        override fun config(configFunc: PreparedStatement.() -> Unit): JdbcService.ServicePreparedStatement {
            configFunc(preparedStatement);
            return this
        }

        override fun execute(): Single<Boolean> = Single.defer { Single.just(preparedStatement.execute()) }
                .subscribeOn(Schedulers.io())
                .observeOn(Swampium.mainThreadScheduler)

        override fun <T> executeQuery(onNext: ResultSet.() -> T): Observable<T> =
                executeQueryToResultSet()
                        .subscribeOn(Schedulers.io())
                        .flatMapObservable { resultSet ->
                            Observable.create<T> { while (resultSet.next()) it.onNext(onNext(resultSet)) }
                        }

        override fun executeQueryToResultSet(): Single<ResultSet> {
            return Single.defer { Single.just(preparedStatement.executeQuery()) }
                    .subscribeOn(Schedulers.io())
        }

        override fun executeBatch(): Single<IntArray> = Single.defer { Single.just(preparedStatement.executeBatch()) }
                .subscribeOn(Schedulers.io())
                .observeOn(Swampium.mainThreadScheduler)

    }

    override fun getConnection(dbUrl: String, dbUsername: String, dbPassword: String, autoCommit: Boolean)
            : Single<out JdbcService.ServiceConnection> {
        return Single.defer {
            Single.just(DriverManager.getConnection(dbUrl, dbUsername, dbPassword))
        }
                .subscribeOn(Schedulers.io())
                .map { ServiceConnectionImpl(it) }
                .doAfterTerminate { }
    }

    override fun init() {
        Class.forName("org.mariadb.jdbc.Driver")
    }

    override fun disable() {
        connectionsMap.values.filter { !it.isClosed }.forEach { it.close() }
    }

    private class ConnectionInfo(
            val dbUrl: String,
            val dbUsername: String,
            val dbPassword: String,
            val autoCommit: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (other !is ConnectionInfo) return false;
            return other.dbUrl == dbUrl
                    && other.dbUsername == dbUsername
                    && other.dbPassword == dbPassword
                    && other.autoCommit == autoCommit
        }

        override fun hashCode(): Int {
            var result = dbUrl.hashCode()
            result = 31 * result + dbUsername.hashCode()
            result = 31 * result + dbPassword.hashCode()
            result = 31 * result + autoCommit.hashCode()
            return result
        }
    }
}
