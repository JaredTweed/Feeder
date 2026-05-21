package com.nononsenseapps.feeder.localtranslation

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class BergamotModelManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun prepareDownloadsDirectLanguagePairAndDeletesIt() =
        runTest {
            server.start()
            val model = "model".toByteArray()
            val lex = "lex".toByteArray()
            val vocab = "vocab".toByteArray()
            val registry = registry("deen", model = model, lex = lex, vocab = vocab)
            server.enqueue(MockResponse().setResponseCode(200).setBody(registry))
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(model)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(lex)))
            server.enqueue(MockResponse().setResponseCode(200).setBody(String(vocab)))

            val manager = modelManager()
            val preparation = manager.prepare(sourceLanguage = "German", targetLanguage = "English")

            assertTrue(preparation is BergamotModelPreparation.Ready)
            val ready = preparation as BergamotModelPreparation.Ready
            assertEquals(listOf("de" to "en"), ready.modelRegistry.map { it.from to it.to })
            assertTrue(
                ready.modelRegistry
                    .single()
                    .files.values
                    .all { it.url?.startsWith("file:") == true },
            )
            assertEquals(BergamotLanguagePairStatus.Downloaded, manager.languagePairStatus("de", "en"))

            manager.deleteLanguagePair("de", "en")

            assertEquals(BergamotLanguagePairStatus.AvailableToDownload, manager.languagePairStatus("de", "en"))
        }

    @Test
    fun prepareUsesEnglishPivotWhenDirectLanguagePairIsUnavailable() =
        runTest {
            server.start()
            val deEnModel = "de-en-model".toByteArray()
            val enFrModel = "en-fr-model".toByteArray()
            val registry =
                buildString {
                    append("{")
                    append(registryEntry("deen", model = deEnModel))
                    append(",")
                    append(registryEntry("enfr", model = enFrModel))
                    append("}")
                }
            server.enqueue(MockResponse().setResponseCode(200).setBody(registry))
            listOf(deEnModel, "lex".toByteArray(), "vocab".toByteArray(), enFrModel, "lex".toByteArray(), "vocab".toByteArray())
                .forEach { server.enqueue(MockResponse().setResponseCode(200).setBody(String(it))) }

            val preparation = modelManager().prepare(sourceLanguage = "de", targetLanguage = "fr")

            assertTrue(preparation is BergamotModelPreparation.Ready)
            val ready = preparation as BergamotModelPreparation.Ready
            assertEquals(listOf("de" to "en", "en" to "fr"), ready.modelRegistry.map { it.from to it.to })
        }

    @Test
    fun prepareFailsWhenDownloadedModelHashDoesNotMatchRegistry() =
        runTest {
            server.start()
            val registry = registry("deen", model = "expected".toByteArray())
            server.enqueue(MockResponse().setResponseCode(200).setBody(registry))
            server.enqueue(MockResponse().setResponseCode(200).setBody("wrong"))

            val preparation = modelManager().prepare(sourceLanguage = "de", targetLanguage = "en")

            assertTrue(preparation is BergamotModelPreparation.Error)
            assertTrue((preparation as BergamotModelPreparation.Error).message.contains("could not download"))
        }

    private fun modelManager(): BergamotModelManager =
        BergamotModelManager(
            modelRoot = temporaryFolder.newFolder(),
            okHttpClient = OkHttpClient(),
            registryUrl = server.url("/registry.json").toString(),
        )

    private fun registry(
        pair: String,
        model: ByteArray,
        lex: ByteArray = "lex".toByteArray(),
        vocab: ByteArray = "vocab".toByteArray(),
    ): String = "{${registryEntry(pair, model = model, lex = lex, vocab = vocab)}}"

    private fun registryEntry(
        pair: String,
        model: ByteArray,
        lex: ByteArray = "lex".toByteArray(),
        vocab: ByteArray = "vocab".toByteArray(),
    ): String =
        """
        "$pair": {
          "model": ${fileJson("$pair/model.bin", model)},
          "lex": ${fileJson("$pair/lex.bin", lex)},
          "vocab": ${fileJson("$pair/vocab.spm", vocab)}
        }
        """.trimIndent()

    private fun fileJson(
        path: String,
        content: ByteArray,
    ): String =
        """
        {
          "name": "${server.url("/$path")}",
          "size": ${content.size},
          "expectedSha256Hash": "${sha256(content)}"
        }
        """.trimIndent()

    private fun sha256(content: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
