package dev.avillagran.ot3android.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.generalDataStore by preferencesDataStore(name = "general_settings")

/** Android client preferences; P20 consumes this flow in the composer and timestamp formatter. */
class GeneralClientPreferences(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.generalDataStore)
    val settings = store.data.map { prefs ->
        JSONObject().also { out -> defaults.keys().forEach { key ->
            val raw = prefs[stringPreferencesKey(key)]
            out.put(key, raw?.let { runCatching { JSONObject(it).get("value") }.getOrNull() } ?: defaults.get(key))
        } }
    }
    suspend fun update(key: String, value: Any) {
        require(defaults.has(key))
        when (key) {
            "followUpBehavior" -> require(value in listOf("queue", "steer"))
            "timestampFormat" -> require(value in listOf("locale", "12-hour", "24-hour"))
            "quickReplyLanguage" -> require(value in languages + "auto")
            "quickRepliesButton", "planModeEnabled" -> require(value is Boolean)
            else -> require(value is String && value.length <= 16_384)
        }
        val normalized = if (key.startsWith("quickReplies.")) (value as String).lineSequence()
            .map { it.trim().take(250) }.filter { it.isNotEmpty() }.take(32).joinToString("\n") else value
        store.edit { it[stringPreferencesKey(key)] = JSONObject().put("value", normalized).toString() }
    }
    suspend fun reset(key: String) { require(defaults.has(key)); store.edit { it.remove(stringPreferencesKey(key)) } }

    fun fields(values: JSONObject): JSONArray = JSONArray().also { fields ->
        fun field(key: String, label: String, kind: String, choices: List<String> = emptyList()) {
            fields.put(JSONObject().put("key", key).put("label", label).put("kind", kind).put("scopeWritable", true)
                .put("value", values.opt(key) ?: defaults.get(key)).put("resetValue", defaults.get(key))
                .put("choices", JSONArray(choices.map { JSONObject().put("id", it).put("name", when(it) {
                    "queue" -> "Queue"; "steer" -> "Steer"; "locale" -> "Locale"; "auto" -> "Automatic"; else -> it
                }) })))
        }
        field("followUpBehavior", "Follow-up behavior", "choice", listOf("queue", "steer"))
        field("timestampFormat", "Time format", "choice", listOf("locale", "12-hour", "24-hour"))
        field("planModeEnabled", "Plan mode (legacy)", "bool")
        field("quickRepliesButton", "Quick replies", "bool")
        field("quickReplyLanguage", "Language", "choice", listOf("auto") + languages)
        languages.forEach { field("quickReplies.$it", "Quick replies ($it)", "text") }
    }
    companion object {
        val languages = listOf("en", "es", "pt", "fr", "de", "it", "nl", "pl", "ru", "uk", "ja", "ko", "zh", "ar", "tr", "sv", "da", "no", "fi", "cs")
        private val quickReplyDefaults = mapOf(
            "nl" to "Ga door.\nGa verder.\nStop.\nControleer de wijzigingen.\nVoer de tests uit.\nLeg het resultaat uit.",
            "pl" to "Kontynuuj.\nDalej.\nZatrzymaj się.\nSprawdź zmiany.\nUruchom testy.\nWyjaśnij wynik.",
            "ru" to "Продолжай.\nДальше.\nОстановись.\nПроверь изменения.\nЗапусти тесты.\nОбъясни результат.",
            "uk" to "Продовжуй.\nДалі.\nЗупинись.\nПеревір зміни.\nЗапусти тести.\nПоясни результат.",
            "ja" to "続けて。\n先に進めて。\n止めて。\n変更を検証して。\nテストを実行して。\n結果を説明して。",
            "ko" to "계속해.\n진행해.\n멈춰.\n변경 사항을 검증해.\n테스트를 실행해.\n결과를 설명해.",
            "zh" to "继续。\n接着做。\n停止。\n验证更改。\n运行测试。\n解释结果。",
            "ar" to "تابع.\nاستمر.\nتوقف.\nتحقق من التغييرات.\nشغّل الاختبارات.\nاشرح النتيجة.",
            "tr" to "Devam et.\nİlerle.\nDur.\nDeğişiklikleri doğrula.\nTestleri çalıştır.\nSonucu açıkla.",
            "sv" to "Fortsätt.\nGå vidare.\nStanna.\nKontrollera ändringarna.\nKör testerna.\nFörklara resultatet.",
            "da" to "Fortsæt.\nGå videre.\nStop.\nKontrollér ændringerne.\nKør testene.\nForklar resultatet.",
            "no" to "Fortsett.\nGå videre.\nStopp.\nKontroller endringene.\nKjør testene.\nForklar resultatet.",
            "fi" to "Jatka.\nJatka eteenpäin.\nPysähdy.\nTarkista muutokset.\nSuorita testit.\nSelitä tulos.",
            "cs" to "Pokračuj.\nDále.\nZastav.\nOvěř změny.\nSpusť testy.\nVysvětli výsledek.",
            "en" to "Continue.\nGo on.\nStop.\nValidate the changes.\nRun the tests.\nExplain the result.",
            "es" to "Continúa.\nSigue.\nDetente.\nValida los cambios.\nEjecuta las pruebas.\nExplica el resultado.",
            "pt" to "Continue.\nProssiga.\nPare.\nValide as alterações.\nExecute os testes.\nExplique o resultado.",
            "fr" to "Continue.\nPoursuis.\nArrête.\nValide les modifications.\nLance les tests.\nExplique le résultat.",
            "de" to "Fahre fort.\nMach weiter.\nStopp.\nPrüfe die Änderungen.\nFühre die Tests aus.\nErkläre das Ergebnis.",
            "it" to "Continua.\nVai avanti.\nFermati.\nVerifica le modifiche.\nEsegui i test.\nSpiega il risultato.",
        )
        val defaults: JSONObject get() = JSONObject().put("followUpBehavior", "queue").put("timestampFormat", "locale")
            .put("planModeEnabled", false).put("quickRepliesButton", true).put("quickReplyLanguage", "auto")
            .also { out -> languages.forEach { out.put("quickReplies.$it", quickReplyDefaults[it].orEmpty()) } }
    }
}
