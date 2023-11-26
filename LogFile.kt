import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

fun demo() {
    val logFile = LogFile("test", 1000)
    runBlocking {
        repeat(1000) {
            logFile.append("test $it")
        }
    }
    val logFileWithGivenSize = logFile.copyContents("test2")
    print(logFileWithGivenSize!!.length()) // will give 1000 or less
}

class LogFile(
    val name: String,
    private val maxFileSizeInBytes: Int,
) {
    private val primaryFile: ResettableLazy<File?> = resettableLazy {
        getOrCreateFile(name)
    }
    private val secondaryFile: ResettableLazy<File?> = resettableLazy {
        getOrCreateFile("$name.backup")
    }

    fun recoverFromErrorIfPossible() {
        if (primaryFile.value == null || !primaryFile.value!!.exists()) {
            primaryFile.reset()
        }
        if (secondaryFile.value == null || !secondaryFile.value!!.exists()) {
            secondaryFile.reset()
        }
    }

    private val lock = Mutex()

    suspend fun append(message: String) {
        recoverFromErrorIfPossible()
        if (primaryFile.value == null || secondaryFile.value == null) {
            return
        }
        lock.withLock {
            try {
                val logEntry = "$Separator$message"
                if (logEntry.length > maxFileSizeInBytes) {
                    return
                }

                val totalLength = primaryFile.value!!.length() + logEntry.length

                if (totalLength > maxFileSizeInBytes) {
                    secondaryFile.value!!.writeText(primaryFile.value!!.readText())
                    coroutineContext.ensureActive()
                    primaryFile.value!!.writeText("")
                }

                primaryFile.value!!.appendText(logEntry)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                e.printStackTrace()
            }
        }
    }

    companion object {
        // random character, it can be anything. but should be uncommon to avoid collapsed logs
        const val Separator = "⚀\n"
        fun mergeLogFiles(outputFileName: String, maxFileSizeInBytes: Int, logFiles: List<LogFile>): File? {
            logFiles.forEach {
                it.recoverFromErrorIfPossible()
            }
            val file = getOrCreateFile(outputFileName) ?: return null
            logFiles.forEach {
                if (it.primaryFile.value == null || it.secondaryFile.value == null) {
                    return null
                }
            }
            try {
                file.writeText("")
                var fileLength = 0

                logFiles.forEach { eachLogFile ->
                    repeat(2) {
                        val content = if (it == 0) {
                            eachLogFile.primaryFile.value!!.readText()
                        } else {
                            eachLogFile.secondaryFile.value!!.readText()
                        }
                        val list = content.split(Separator).reversed()
                        for (i in list.indices) {
                            val line = "${list[i]}\n"
                            fileLength += line.length
                            if (fileLength > maxFileSizeInBytes) {
                                break
                            }
                            file.appendText(line)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                e.printStackTrace()
            }
            return file
        }

        private fun getOrCreateFile(name: String): File? {
            try {
                var file = File(FileUtils.downloadFolderPath)
                if (!file.exists()) {
                    file.mkdirs()
                }
                file = File(
                    file,
                    name
                )
                if (!file.exists()) {
                    file.createNewFile()
                }
                return file
            } catch (e: IOException) {
                return null
            }
        }
    }

    fun copyContents(name: String): File? {
        return mergeLogFiles(name, maxFileSizeInBytes, listOf(this))
    }
}

object UninitializedValue

fun <T> resettableLazyTimeExpired(cacheExpiryTime: Long, initializer: () -> T): ResettableLazy<T> = ResettableLazyImpl(initializer, cacheExpiryTime)
fun <T> resettableLazy(initializer: () -> T): ResettableLazy<T> = ResettableLazyImpl(initializer)
fun <T> resettableLazy(lock: Any? = null, initializer: () -> T): ResettableLazy<T> = ResettableLazyImpl(initializer)

fun <T> resettableLazyUnSynchronized(initializer: () -> T): ResettableLazy<T> = ResettableLazyUnSynchronizedImpl(initializer)

interface ResettableLazy<T> {
    val value: T

    fun isInitialized(): Boolean
    fun reset()
}

private class ResettableLazyImpl<T>(private val initializer: () -> T, lock: Any? = null, private val cacheExpiryTime: Long = 0L) :
    ResettableLazy<T> {
    /**
     * This is an extended version of Kotlin Lazy property [kotlin.SynchronizedLazyImpl]
     * calling reset() will set UninitializedValue
     * if the values are used after reset() call, the value will be initialised again
     */
    @Volatile private var _value: Any? = UninitializedValue
    // final field is required to enable safe publication of constructed instance
    private val lock = lock ?: this
    private var generatedTime = 0L

    override val value: T
        get() {
            if (cacheExpiryTime != 0L && generatedTime != 0L && System.currentTimeMillis() - generatedTime > cacheExpiryTime) {
                reset()
            }
            var tempValue = _value
            if (tempValue !== UninitializedValue) {
                @Suppress("UNCHECKED_CAST")
                return tempValue as T
            }

            return synchronized(lock) {
                tempValue = _value
                if (tempValue !== UninitializedValue) {
                    @Suppress("UNCHECKED_CAST") (tempValue as T)
                } else {
                    val typedValue = initializer()
                    generatedTime = System.currentTimeMillis()
                    _value = typedValue
                    typedValue
                }
            }
        }

    override fun reset() {
        synchronized(lock) {
            _value = UninitializedValue
        }
    }

    override fun isInitialized(): Boolean = _value !== UninitializedValue

    override fun toString(): String = if (isInitialized()) value.toString() else "Lazy value not initialized yet."
}

private class ResettableLazyUnSynchronizedImpl<T>(private val initializer: () -> T) :
    ResettableLazy<T> {
    /**
     * This is a downgraded version of Kotlin Lazy property [ResettableLazyImpl], use it if everything happens in main thread
     */
    private var _value: Any? = UninitializedValue

    override val value: T
        get() {
            if (_value === UninitializedValue) {
                _value = initializer()
            }
            @Suppress("UNCHECKED_CAST")
            return _value as T
        }

    override fun reset() {
        _value = UninitializedValue
    }

    override fun isInitialized(): Boolean = _value !== UninitializedValue

    override fun toString(): String = if (isInitialized()) value.toString() else "Lazy value not initialized yet."
}
