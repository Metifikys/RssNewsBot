package metifikys.db

import org.jetbrains.exposed.sql.Table
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards [Schema.ALL_TABLES]: a table object added to `metifikys.db` but not registered there
 * would never be created by `createMissingTablesAndColumns`, and the first query against it
 * would fail only in production. The package is scanned from the compiled classes.
 */
class SchemaTest {

    @Test
    fun `ALL_TABLES registers every Table object in the db package`() {
        val declared = tableObjectsInPackage()
        assertTrue(declared.isNotEmpty(), "found no Table objects — the class scan is broken")

        val registered = Schema.ALL_TABLES.toList()
        assertEquals(registered.size, registered.toSet().size, "ALL_TABLES lists a table twice")

        val missing = declared.filter { it !in registered }.map { it.tableName }
        assertTrue(missing.isEmpty(), "Table objects missing from Schema.ALL_TABLES: $missing")
        val stray = registered.filter { it !in declared }.map { it.tableName }
        assertTrue(stray.isEmpty(), "ALL_TABLES lists tables outside metifikys.db: $stray")
    }

    /** Every Kotlin `object … : Table` compiled into the package of [ArticlesTable]. */
    private fun tableObjectsInPackage(): Set<Table> {
        val pkg = ArticlesTable::class.java.packageName
        val pkgPath = pkg.replace('.', '/')
        val location = File(ArticlesTable::class.java.protectionDomain.codeSource.location.toURI())
        val classNames = if (location.isDirectory) {
            File(location, pkgPath).listFiles().orEmpty()
                .filter { it.name.endsWith(".class") }
                .map { "$pkg.${it.name.removeSuffix(".class")}" }
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("$pkgPath/") && it.endsWith(".class") && '/' !in it.removePrefix("$pkgPath/") }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .toList()
            }
        }
        return classNames
            .map { Class.forName(it, false, ArticlesTable::class.java.classLoader) }
            .filter { Table::class.java.isAssignableFrom(it) }
            .mapNotNull { cls -> cls.declaredFields.firstOrNull { it.name == "INSTANCE" }?.get(null) as? Table }
            .toSet()
    }
}
