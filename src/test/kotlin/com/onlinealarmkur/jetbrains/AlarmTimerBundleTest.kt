package com.onlinealarmkur.jetbrains

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Locale
import java.util.Properties

class AlarmTimerBundleTest {
    @Test
    fun `bundles have exactly 135 duplicate free matching nonblank keys`() {
        val english = load(BASE)
        assertEquals(135, english.size)
        assertEquals(english.size, rawKeys(BASE).size)
        RESOURCES.forEach { (_, resource) ->
            val localized = load(resource)
            assertEquals(135, localized.size, resource)
            assertEquals(localized.size, rawKeys(resource).size, "$resource duplicate keys")
            assertEquals(english.keys, localized.keys, "$resource parity")
            english.stringPropertyNames().forEach { key ->
                assertFalse(localized.getProperty(key).isBlank(), "$resource blank $key")
                assertEquals(placeholders(english.getProperty(key)), placeholders(localized.getProperty(key)), "$resource $key placeholders")
            }
        }
        listOf("status.completed", "status.missed", "notification.finished", "notification.body", "accessible.timer.preset", "panel.item.paused").forEach {
            assertFalse(english.containsKey(it), "legacy $it")
        }
    }

    @Test
    fun `all parameterized values format and semantic copy is complete`() {
        RESOURCES.forEach { (locale, resource) ->
            val bundle = load(resource)
            bundle.forEach { key, raw ->
                val value = raw as String
                val count = placeholders(value).maxOfOrNull { it.drop(1).dropLast(1).toInt() }?.plus(1) ?: 0
                assertFalse(PLACEHOLDER.containsMatchIn(MessageFormat(value, locale).format(Array<Any>(count) { "value-$it" })), "$locale $key")
            }
            EXPECTED.getValue(locale).let { expected ->
                STATUS_KEYS.zip(expected.statuses).forEach { (key, value) -> assertEquals(value, bundle.getProperty(key), "$locale $key") }
                assertEquals(expected.alarmBody, bundle.getProperty("notification.alarm.body"))
                assertEquals(expected.timerBody, bundle.getProperty("notification.timer.body"))
                assertEquals(expected.singular, bundle.getProperty("accessible.timer.preset.one.minute"))
                assertEquals(expected.plural, MessageFormat(bundle.getProperty("accessible.timer.preset.minutes"), locale).format(arrayOf(5)))
                assertEquals(expected.duration, MessageFormat(bundle.getProperty("duration.display.hours.minutes"), locale).format(arrayOf<Any>(1, "01")))
            }
            VISIBLE_COPY.getValue(locale).let { expected ->
                assertEquals(expected.clearHistory, bundle.getProperty("action.AlarmTimer.ClearCompleted.text"), "$locale clear history")
                assertEquals(expected.dateComment, bundle.getProperty("panel.date.comment"), "$locale date comment")
            }
        }
    }

    @Test
    fun `English capitalization effective fallback and high risk copy are exact`() {
        val english = load(BASE)
        mapOf("settings.test.sound" to "Test Sound", "panel.section.active" to "Active Items", "panel.section.history" to "Completed or Missed Items", "panel.set.alarm" to "Set Alarm", "panel.save.alarm" to "Save Alarm", "panel.cancel.edit" to "Cancel Edit", "panel.start.timer" to "Start Timer", "panel.edit.alarm" to "Edit Alarm", "panel.clear.all" to "Clear All").forEach { (key, value) -> assertEquals(value, english.getProperty(key)) }
        assertEquals("일광 절약 시간제로 인해 {1} 시간대에는 다음 현지 시간이 존재하지 않습니다: {0}", load("/messages/AlarmTimerBundle_ko.properties").getProperty("alarm.date.time.error.dst.gap"))
        assertEquals("Приостановлен · {0}", load("/messages/AlarmTimerBundle_ru.properties").getProperty("status.paused"))
        assertEquals("Dokümantasyonu aç", load("/messages/AlarmTimerBundle_tr.properties").getProperty("action.AlarmTimer.OpenDocumentation.text"))
        assertEquals("Default: 5m; maximum: 24h. After an IDE restart or system wake, items overdue by more than this window are not alerted and are marked as missed. Use 0s to alert only items that are not yet overdue.", english.getProperty("settings.overdue.comment"))
        assertEquals("Standard: 5m; Maximum: 24h. Nach einem IDE-Neustart oder dem Aufwecken des Systems werden Einträge, die länger als dieses Zeitfenster überfällig sind, nicht benachrichtigt und als verpasst markiert. Verwenden Sie 0s, um nur Einträge zu benachrichtigen, die noch nicht überfällig sind.", load("/messages/AlarmTimerBundle_de.properties").getProperty("settings.overdue.comment"))
        assertEquals("Predeterminado: 5m; máximo: 24h. Tras reiniciar el IDE o reactivar el sistema, no se envía ninguna notificación para los elementos que superen este intervalo de retraso; se marcan como omitidos. Usa 0s para notificar solo los elementos que aún no estén vencidos.", load("/messages/AlarmTimerBundle_es.properties").getProperty("settings.overdue.comment"))
        val french = load("/messages/AlarmTimerBundle_fr.properties")
        assertEquals("Mettre en pause / Reprendre", french.getProperty("panel.pause.resume"))
        assertEquals("Valeur par défaut : 5m ; maximum : 24h. Après un redémarrage de l’IDE ou la sortie de veille, aucune notification n’est affichée pour les éléments dont le retard dépasse cet intervalle ; ils sont marqués comme manqués. Utilisez 0s pour notifier uniquement les éléments qui ne sont pas encore en retard.", french.getProperty("settings.overdue.comment"))
        assertEquals("Padrão: 5m; máximo: 24h. Após reiniciar o IDE ou reativar o sistema, itens atrasados além desse intervalo não geram notificação e são marcados como perdidos. Use 0s para notificar somente itens que ainda não estejam atrasados.", load("/messages/AlarmTimerBundle_pt_BR.properties").getProperty("settings.overdue.comment"))
        RESOURCES.forEach { (_, resource) ->
            val about = load(resource).getProperty("settings.about.version")
            assertTrue(about.endsWith("MIT License"), resource)
            assertTrue(about.contains("{0}"), "$resource about version placeholder")
        }
        assertEquals(Locale.ENGLISH, AlarmTimerLocale.resolve(Locale.forLanguageTag("pt-PT")))
        assertEquals(Locale.SIMPLIFIED_CHINESE, AlarmTimerLocale.resolve(Locale.forLanguageTag("zh-SG")))
        assertEquals(Locale.ENGLISH, AlarmTimerLocale.resolve(Locale.forLanguageTag("zh-TW")))
        assertEquals(Locale.ENGLISH, AlarmTimerLocale.resolve(Locale.ITALIAN))
    }

    @Test
    fun `production bundle lookup honors effective locale mnemonics and default isolation`() {
        val defaultLocale = Locale.getDefault()
        assertEquals("Alarm & Timer", AlarmTimerBundle.messageFor(Locale.ENGLISH, "settings.display.name"))
        assertEquals("1h 01m", AlarmTimerBundle.messageFor(Locale.ENGLISH, "duration.display.hours.minutes", 1, "01"))
        assertEquals("设定时间已到。", AlarmTimerBundle.messageFor(Locale.forLanguageTag("zh-SG"), "notification.alarm.body"))
        assertEquals("It’s time.", AlarmTimerBundle.messageFor(Locale.forLanguageTag("pt-PT"), "notification.alarm.body"))
        assertEquals("It’s time.", AlarmTimerBundle.messageFor(Locale.forLanguageTag("zh-TW"), "notification.alarm.body"))
        assertEquals("It’s time.", AlarmTimerBundle.messageFor(Locale.ITALIAN, "notification.alarm.body"))
        assertEquals(defaultLocale, Locale.getDefault())
    }

    @Test
    fun `formal product branding resolves identically in every locale`() {
        val formalKeys = listOf(
            "settings.display.name",
            "toolwindow.stripe.Alarm_&_Timer",
            "group.AlarmTimer.ToolsMenu.text",
            "panel.error.title",
            "status.widget.display.name",
        )
        RESOURCES.keys.forEach { locale ->
            formalKeys.forEach { key -> assertEquals("Alarm & Timer", AlarmTimerBundle.messageFor(locale, key), "$locale $key") }
            assertTrue(AlarmTimerBundle.messageFor(locale, "action.AlarmTimer.Open.text").contains("Alarm & Timer"), "$locale action")
        }
    }

    private fun load(resource: String): Properties {
        val stream = javaClass.getResourceAsStream(resource)
        assertNotNull(stream, resource)
        return Properties().apply { InputStreamReader(stream!!, StandardCharsets.UTF_8).use(::load) }
    }

    private fun rawKeys(resource: String): List<String> = javaClass.getResourceAsStream(resource)!!.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith('#') && !it.startsWith('!') }.map { it.substringBefore('=').trim() }.toList()
    }

    private fun placeholders(value: String) = PLACEHOLDER.findAll(value).map { it.value }.toSet()

    private data class Expected(val statuses: List<String>, val alarmBody: String, val timerBody: String, val singular: String, val plural: String, val duration: String)

    private data class VisibleCopy(
        val clearHistory: String,
        val dateComment: String,
    )

    private companion object {
        const val BASE = "/messages/AlarmTimerBundle.properties"
        val PLACEHOLDER = Regex("""\{\d+}""")
        val RESOURCES = linkedMapOf(Locale.ENGLISH to BASE, Locale.GERMAN to "/messages/AlarmTimerBundle_de.properties", Locale.forLanguageTag("es") to "/messages/AlarmTimerBundle_es.properties", Locale.FRENCH to "/messages/AlarmTimerBundle_fr.properties", Locale.JAPANESE to "/messages/AlarmTimerBundle_ja.properties", Locale.KOREAN to "/messages/AlarmTimerBundle_ko.properties", Locale.forLanguageTag("pt-BR") to "/messages/AlarmTimerBundle_pt_BR.properties", Locale.forLanguageTag("ru") to "/messages/AlarmTimerBundle_ru.properties", Locale.forLanguageTag("tr") to "/messages/AlarmTimerBundle_tr.properties", Locale.SIMPLIFIED_CHINESE to "/messages/AlarmTimerBundle_zh_CN.properties")
        val STATUS_KEYS = listOf("status.alarm.triggered", "status.alarm.missed", "status.timer.finished", "status.timer.missed")
        val VISIBLE_COPY = linkedMapOf(
            Locale.ENGLISH to VisibleCopy("Clear History", "Optional, YYYY-MM-DD. Leave blank for the next occurrence (today if the time is still ahead, otherwise tomorrow)."),
            Locale.GERMAN to VisibleCopy("Verlauf löschen", "Optional, JJJJ-MM-TT. Leer lassen für den nächsten Zeitpunkt (heute, wenn die Uhrzeit noch bevorsteht, andernfalls morgen)."),
            Locale.forLanguageTag("es") to VisibleCopy("Borrar historial", "Opcional, AAAA-MM-DD. Déjalo en blanco para la próxima vez (hoy si la hora aún no ha pasado; de lo contrario, mañana)."),
            Locale.FRENCH to VisibleCopy("Effacer l’historique", "Facultatif, AAAA-MM-JJ. Laissez vide pour la prochaine occurrence (aujourd’hui si l’heure n’est pas encore passée, sinon demain)."),
            Locale.JAPANESE to VisibleCopy("履歴を消去", "省略可能、YYYY-MM-DD。空欄の場合は次の該当時刻（その時刻がまだ先なら今日、それ以外は明日）になります。"),
            Locale.KOREAN to VisibleCopy("기록 지우기", "선택 사항, YYYY-MM-DD. 비워 두면 다음 해당 시각으로 설정됩니다(아직 지나지 않았으면 오늘, 그렇지 않으면 내일)."),
            Locale.forLanguageTag("pt-BR") to VisibleCopy("Limpar histórico", "Opcional, AAAA-MM-DD. Deixe em branco para a próxima ocorrência (hoje se o horário ainda não passou; caso contrário, amanhã)."),
            Locale.forLanguageTag("ru") to VisibleCopy("Очистить историю", "Необязательно, ГГГГ-ММ-ДД. Оставьте поле пустым для ближайшего такого времени (сегодня, если оно ещё не наступило, иначе завтра)."),
            Locale.forLanguageTag("tr") to VisibleCopy("Geçmişi temizle", "İsteğe bağlı, YYYY-AA-GG. Bir sonraki uygun zaman için boş bırakın (saat henüz geçmediyse bugün, aksi takdirde yarın)."),
            Locale.SIMPLIFIED_CHINESE to VisibleCopy("清除历史记录", "可选，YYYY-MM-DD。留空则使用下一次出现的该时间（若该时间尚未到，则为今天，否则为明天）。"),
        )
        val EXPECTED = linkedMapOf(
            Locale.ENGLISH to Expected(listOf("Triggered", "Missed · no alert sent", "Finished", "Missed · no alert sent"), "It’s time.", "Time is up.", "Start 1-minute timer", "Start 5-minute timer", "1h 01m"),
            Locale.GERMAN to Expected(listOf("Ausgelöst", "Verpasst · keine Benachrichtigung gesendet", "Abgelaufen", "Verpasst · keine Benachrichtigung gesendet"), "Es ist so weit.", "Die Zeit ist abgelaufen.", "1-Minuten-Timer starten", "5-Minuten-Timer starten", "1 Std. 01 Min."),
            Locale.forLanguageTag("es") to Expected(listOf("Activada", "Omitida · no se envió ninguna notificación", "Finalizado", "Omitido · no se envió ninguna notificación"), "Es la hora.", "Se acabó el tiempo.", "Iniciar temporizador de 1 minuto", "Iniciar temporizador de 5 minutos", "1 h 01 min"),
            Locale.FRENCH to Expected(listOf("Déclenchée", "Manquée · aucune notification envoyée", "Terminé", "Manqué · aucune notification envoyée"), "C’est l’heure.", "Le temps est écoulé.", "Démarrer un minuteur d’une minute", "Démarrer un minuteur de 5 minutes", "1 h 01 min"),
            Locale.JAPANESE to Expected(listOf("作動済み", "未通知", "完了", "未通知"), "設定時刻になりました。", "終了しました。", "1 分タイマーを開始", "5 分タイマーを開始", "1 時間 01 分"),
            Locale.KOREAN to Expected(listOf("작동됨", "알림 없음", "완료됨", "놓침"), "설정한 시간이 되었습니다.", "종료되었습니다.", "1분 타이머 시작", "5분 타이머 시작", "1시간 01분"),
            Locale.forLanguageTag("pt-BR") to Expected(listOf("Disparou", "Não disparou · nenhuma notificação enviada", "Concluído", "Expirou · nenhuma notificação enviada"), "Está na hora.", "O tempo acabou.", "Iniciar temporizador de 1 minuto", "Iniciar temporizador de 5 minutos", "1h 01min"),
            Locale.forLanguageTag("ru") to Expected(listOf("Сработал", "Не сработал", "Завершён", "Пропущен"), "Сработал.", "Завершён.", "Запустить таймер на 1 минуту", "Запустить таймер на 5 минут", "1 ч 01 мин"),
            Locale.forLanguageTag("tr") to Expected(listOf("Çaldı", "Çalmadı", "Tamamlandı", "Kaçırıldı"), "Çaldı.", "Tamamlandı.", "1 dakikalık zamanlayıcı başlat", "5 dakikalık zamanlayıcı başlat", "1 sa 01 dk"),
            Locale.SIMPLIFIED_CHINESE to Expected(listOf("已触发", "未提醒", "已完成", "已错过"), "设定时间已到。", "计时结束。", "启动 1 分钟计时器", "启动 5 分钟计时器", "1 小时 01 分钟"),
        )
    }
}
