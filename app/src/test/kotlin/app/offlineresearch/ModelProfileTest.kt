package app.offlineresearch

import app.offlineresearch.profiles.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelProfileTest {

    // Unit tests run with the module directory (app/) as the working directory.
    private fun shipped(name: String) = ModelProfile.parse(File("../profiles/$name").readText())

    @Test
    fun shippedProfilesParse() {
        for (name in listOf("low.json", "high.json")) {
            val profile = shipped(name)
            assertTrue("$name: model_file must be a GGUF", profile.modelFile.endsWith(".gguf"))
            assertTrue("$name: n_ctx", profile.contextSize > 0)
            assertTrue("$name: max_tokens must fit in n_ctx", profile.maxTokens < profile.contextSize)
        }
    }

    @Test
    fun shippedProfilesStreamFromFlashByDefault() {
        for (name in listOf("low.json", "high.json")) {
            val profile = shipped(name)
            assertTrue("$name: mmap must be on", profile.useMmap)
            assertFalse("$name: mlock must be off", profile.useMlock)
        }
    }

    @Test
    fun optionalFieldsHaveDefaultsAndUnknownKeysAreIgnored() {
        val profile = ModelProfile.parse(
            """{"name":"t","model_file":"m.gguf","n_ctx":2048,"n_batch":256,
               "max_tokens":128,"temperature":0.7,"top_k":20,"top_p":0.8,"future_field":1}""",
        )
        assertEquals(0, profile.threads)
        assertEquals("auto", profile.chatTemplate)
        assertTrue(profile.useMmap)
        assertFalse(profile.useMlock)
    }
}
