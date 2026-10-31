package dev.avillagran.ot3android.core.providers

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPresentationTest {
    @Test fun searchIncludesProviderSubProviderAndSlugWithMultipleWordsAndFuzzyMatch() {
        val row = JSONObject("""{"displayName":"Fast model","model":"gpt-6","providerName":"Personal","subProvider":"OpenAI"}""")
        assertTrue(ModelPresentation.search(row, "personal gpt") >= 0)
        assertTrue(ModelPresentation.search(row, "openai") >= 0)
        assertTrue(ModelPresentation.search(row, "fst") >= 0)
        assertEquals(-1, ModelPresentation.search(row, "missing"))
    }
    @Test fun explicitFalseOverridesDefaultAndUnavailableChoiceIsPreserved() {
        val descriptor = JSONObject("""{"id":"fast","type":"boolean","currentValue":true}""")
        val selection = JSONObject("""{"options":[{"id":"fast","value":false}]}""")
        assertEquals(false, ModelPresentation.value(descriptor, selection))
        assertTrue(ModelPresentation.available(descriptor, false))
        assertFalse(ModelPresentation.available(descriptor, "false"))
        assertEquals(true, ModelPresentation.value(descriptor, JSONObject()))
        val select = JSONObject("""{"id":"effort","type":"select","currentValue":"removed","options":[{"id":"high"}]}""")
        assertFalse(ModelPresentation.available(select, ModelPresentation.value(select, JSONObject())))
        assertTrue(ModelPresentation.available(select, "high"))
    }
}
