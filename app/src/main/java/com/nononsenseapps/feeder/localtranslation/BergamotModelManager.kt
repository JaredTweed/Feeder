package com.nononsenseapps.feeder.localtranslation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class BergamotModelManager(
    private val modelRoot: File,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val registryUrl: String = DEFAULT_REGISTRY_URL,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = false
        }
    private val registryFile: File = modelRoot.resolve("registry.json")
    private val downloadMutex = Mutex()

    suspend fun prepare(
        sourceLanguage: String,
        targetLanguage: String,
    ): BergamotModelPreparation =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                val source = normalizeLanguageCode(sourceLanguage)
                val target = normalizeLanguageCode(targetLanguage)
                val registry =
                    loadRegistry()
                        ?: return@withLock BergamotModelPreparation.Error(
                            "Android system translation is unavailable, and Feeder has not downloaded the Bergamot model registry yet. Connect once to download app-provided offline model metadata.",
                        )

                val path =
                    resolvePath(registry, source, target)
                        ?: return@withLock BergamotModelPreparation.Error(
                            "Android system translation is unavailable, and Feeder does not have an app-provided Bergamot model path for $source to $target.",
                        )

                val entries = mutableListOf<BergamotModelRegistryEntry>()
                for (entry in path) {
                    val downloaded =
                        ensureDownloaded(entry)
                            ?: return@withLock BergamotModelPreparation.Error(
                                "Android system translation is unavailable, and Feeder could not download the app-provided Bergamot model for ${entry.from} to ${entry.to}.",
                            )
                    entries += downloaded
                }

                BergamotModelPreparation.Ready(entries)
            }
        }

    suspend fun languagePairStatus(
        sourceLanguage: String,
        targetLanguage: String,
    ): BergamotLanguagePairStatus =
        withContext(Dispatchers.IO) {
            val source = normalizeLanguageCode(sourceLanguage)
            val target = normalizeLanguageCode(targetLanguage)
            val registry = loadRegistry() ?: return@withContext BergamotLanguagePairStatus.RegistryMissing
            val path = resolvePath(registry, source, target) ?: return@withContext BergamotLanguagePairStatus.Unavailable
            if (path.all(::isDownloaded)) {
                BergamotLanguagePairStatus.Downloaded
            } else {
                BergamotLanguagePairStatus.AvailableToDownload
            }
        }

    suspend fun deleteLanguagePair(
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        withContext(Dispatchers.IO) {
            val source = normalizeLanguageCode(sourceLanguage)
            val target = normalizeLanguageCode(targetLanguage)
            val registry = loadRegistry(allowNetwork = false) ?: return@withContext
            resolvePath(registry, source, target)
                ?.forEach { entry -> pairDir(entry.from, entry.to).deleteRecursively() }
        }
    }

    fun storageLocation(): File = modelRoot

    private fun loadRegistry(allowNetwork: Boolean = true): List<BergamotModelRegistryEntry>? {
        registryFile
            .takeIf(File::isFile)
            ?.readText()
            ?.let(::parseRegistry)
            ?.let { return it }

        if (allowNetwork) {
            fetchRegistry()?.let { return it }
        }

        return null
    }

    private fun fetchRegistry(): List<BergamotModelRegistryEntry>? =
        runCatching {
            val request = Request.Builder().url(registryUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching null
                }
                response.body.string().also { body ->
                    registryFile.parentFile?.mkdirs()
                    registryFile.writeText(body)
                }
            }
        }.getOrNull()?.let(::parseRegistry)

    private fun parseRegistry(content: String): List<BergamotModelRegistryEntry>? =
        runCatching {
            json
                .parseToJsonElement(content)
                .jsonObject
                .mapNotNull { (key, value) ->
                    val normalizedKey = key.lowercase(Locale.ROOT)
                    if (normalizedKey.length < 4) {
                        return@mapNotNull null
                    }
                    val from = normalizedKey.substring(0, 2)
                    val to = normalizedKey.substring(2, 4)
                    val files = parseModelFiles(value.jsonObject, from, to)
                    BergamotModelRegistryEntry(
                        from = from,
                        to = to,
                        files = files,
                    )
                }
        }.getOrNull()

    private fun parseModelFiles(
        files: JsonObject,
        from: String,
        to: String,
    ): Map<String, BergamotModelFile> =
        files
            .mapNotNull { (part, value) ->
                val fileObject = value as? JsonObject ?: return@mapNotNull null
                val name =
                    fileObject["name"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: return@mapNotNull null
                val remoteUrl =
                    if (name.startsWith("http://", ignoreCase = true) || name.startsWith("https://", ignoreCase = true)) {
                        name
                    } else {
                        "$DEFAULT_MODEL_BASE_URL/$from$to/$name"
                    }
                part to
                    BergamotModelFile(
                        name = name.substringAfterLast('/'),
                        remoteUrl = remoteUrl,
                        size = fileObject["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                        expectedSha256Hash = fileObject["expectedSha256Hash"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        config = fileObject["config"],
                    )
            }.toMap()

    private fun resolvePath(
        registry: List<BergamotModelRegistryEntry>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<BergamotModelRegistryEntry>? {
        registry.firstOrNull { it.from == sourceLanguage && it.to == targetLanguage }?.let { return listOf(it) }

        if (sourceLanguage == PIVOT_LANGUAGE || targetLanguage == PIVOT_LANGUAGE) {
            return null
        }

        val outbound = registry.firstOrNull { it.from == sourceLanguage && it.to == PIVOT_LANGUAGE }
        val inbound = registry.firstOrNull { it.from == PIVOT_LANGUAGE && it.to == targetLanguage }
        return if (outbound != null && inbound != null) {
            listOf(outbound, inbound)
        } else {
            null
        }
    }

    private fun ensureDownloaded(entry: BergamotModelRegistryEntry): BergamotModelRegistryEntry? {
        val downloadedFiles = mutableMapOf<String, BergamotModelFile>()
        val dir = pairDir(entry.from, entry.to)
        dir.mkdirs()

        entry.files.forEach { (part, file) ->
            val localFile = dir.resolve(file.name.sanitizedModelFileName())
            val ready =
                if (localFile.isFile && file.matchesSha256(localFile)) {
                    true
                } else {
                    downloadFile(file, localFile)
                }
            if (!ready) {
                return null
            }
            downloadedFiles[part] = file.copy(url = localFile.toURI().toString())
        }

        val downloadedEntry = entry.copy(files = downloadedFiles)
        dir.resolve("model.json").writeText(json.encodeToString(downloadedEntry))
        return downloadedEntry
    }

    private fun downloadFile(
        modelFile: BergamotModelFile,
        destination: File,
    ): Boolean =
        runCatching {
            val request = Request.Builder().url(modelFile.remoteUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching false
                }
                val tempFile = File(destination.parentFile, "${destination.name}.download")
                response.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                if (!modelFile.matchesSha256(tempFile)) {
                    tempFile.delete()
                    return@runCatching false
                }
                destination.parentFile?.mkdirs()
                tempFile.renameTo(destination)
            }
        }.getOrDefault(false)

    private fun isDownloaded(entry: BergamotModelRegistryEntry): Boolean =
        entry.files.values.all { file ->
            val localFile = pairDir(entry.from, entry.to).resolve(file.name.sanitizedModelFileName())
            localFile.isFile && file.matchesSha256(localFile)
        }

    private fun pairDir(
        from: String,
        to: String,
    ): File = modelRoot.resolve("$from-$to")

    private fun BergamotModelFile.matchesSha256(file: File): Boolean = expectedSha256Hash.isBlank() || sha256(file).equals(expectedSha256Hash, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun String.sanitizedModelFileName(): String =
        substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .ifBlank { "model-file" }

    companion object {
        private const val DEFAULT_REGISTRY_URL = "https://bergamot.s3.amazonaws.com/models/index.json"
        private const val DEFAULT_MODEL_BASE_URL = "https://bergamot.s3.amazonaws.com/models"
        private const val PIVOT_LANGUAGE = "en"
    }
}

sealed interface BergamotModelPreparation {
    data class Ready(
        val modelRegistry: List<BergamotModelRegistryEntry>,
    ) : BergamotModelPreparation

    data class Error(
        val message: String,
    ) : BergamotModelPreparation
}

enum class BergamotLanguagePairStatus {
    RegistryMissing,
    Unavailable,
    AvailableToDownload,
    Downloaded,
}

@Serializable
data class BergamotModelRegistryEntry(
    val from: String,
    val to: String,
    val files: Map<String, BergamotModelFile>,
)

@Serializable
data class BergamotModelFile(
    val name: String,
    val remoteUrl: String,
    val size: Long,
    val expectedSha256Hash: String = "",
    val url: String? = null,
    val config: JsonElement? = null,
)
