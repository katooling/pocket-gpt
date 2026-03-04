package com.pocketagent.memory

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * JVM SQLite-backed memory module for stage-5 persistence and pruning validation.
 */
class SqliteMemoryModule private constructor(
    private val jdbcUrl: String,
) : MemoryModule {
    init {
        ensureDriver()
        initializeSchema()
    }

    override fun saveMemoryChunk(chunk: MemoryChunk): Boolean {
        if (chunk.id.isBlank() || chunk.content.isBlank()) {
            return false
        }
        return runCatching {
            withConnection { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO memory_chunks (id, content, created_at_epoch_ms)
                    VALUES (?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        content = excluded.content,
                        created_at_epoch_ms = excluded.created_at_epoch_ms
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, chunk.id)
                    statement.setString(2, chunk.content)
                    statement.setLong(3, chunk.createdAtEpochMs)
                    statement.executeUpdate()
                }
            }
        }.isSuccess
    }

    override fun retrieveRelevantMemory(query: String, limit: Int): List<MemoryChunk> {
        if (limit <= 0) {
            return emptyList()
        }
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) {
            return emptyList()
        }

        val ranked = loadAllChunks()
            .map { chunk -> chunk to overlapScore(queryTokens, tokenize(chunk.content)) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<MemoryChunk, Int>> { it.second }
                    .thenByDescending { it.first.createdAtEpochMs }
                    .thenBy { it.first.id },
            )

        return ranked.take(limit).map { it.first }
    }

    override fun pruneMemory(maxChunks: Int): Int {
        if (maxChunks < 0) {
            return 0
        }

        return withConnection { connection ->
            connection.autoCommit = false
            try {
                val currentCount = readChunkCount(connection)
                val toRemove = (currentCount - maxChunks).coerceAtLeast(0)
                if (toRemove <= 0) {
                    connection.commit()
                    return@withConnection 0
                }

                val deleted = connection.prepareStatement(
                    """
                    DELETE FROM memory_chunks
                    WHERE id IN (
                        SELECT id
                        FROM memory_chunks
                        ORDER BY created_at_epoch_ms ASC, id ASC
                        LIMIT ?
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, toRemove)
                    statement.executeUpdate()
                }
                connection.commit()
                deleted
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun loadAllChunks(): List<MemoryChunk> {
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, content, created_at_epoch_ms
                FROM memory_chunks
                ORDER BY created_at_epoch_ms DESC, id DESC
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val chunks = mutableListOf<MemoryChunk>()
                    while (rows.next()) {
                        chunks.add(
                            MemoryChunk(
                                id = rows.getString("id"),
                                content = rows.getString("content"),
                                createdAtEpochMs = rows.getLong("created_at_epoch_ms"),
                            ),
                        )
                    }
                    chunks
                }
            }
        }
    }

    private fun readChunkCount(connection: Connection): Int {
        return connection.prepareStatement("SELECT COUNT(*) FROM memory_chunks").use { statement ->
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    0
                } else {
                    rows.getInt(1)
                }
            }
        }
    }

    private fun initializeSchema() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS memory_chunks (
                        id TEXT PRIMARY KEY,
                        content TEXT NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_memory_chunks_created_at
                    ON memory_chunks (created_at_epoch_ms, id)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun ensureDriver() {
        runCatching { Class.forName(SQLITE_DRIVER_CLASS) }
            .getOrElse { error ->
                throw IllegalStateException(
                    "SQLite JDBC driver unavailable. Add org.xerial:sqlite-jdbc to runtime classpath.",
                    error,
                )
            }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun overlapScore(a: Set<String>, b: Set<String>): Int {
        return a.intersect(b).size
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection(jdbcUrl).use(block)
    }

    companion object {
        private const val SQLITE_DRIVER_CLASS = "org.sqlite.JDBC"

        fun fromPath(databasePath: String): SqliteMemoryModule {
            require(databasePath.isNotBlank()) { "databasePath must not be blank." }
            return fromFile(Path.of(databasePath))
        }

        fun fromFile(databasePath: Path): SqliteMemoryModule {
            val normalizedPath = databasePath.toAbsolutePath().normalize()
            normalizedPath.parent?.let { Files.createDirectories(it) }
            return SqliteMemoryModule("jdbc:sqlite:$normalizedPath")
        }
    }
}
