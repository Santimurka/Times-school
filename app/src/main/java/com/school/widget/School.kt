package com.school.widget

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class Lesson(val name: String, val room: String, val s: Int, val e: Int)

/* ================= РАСПИСАНИЕ — НЕ ИЗМЕНЕНО ================= */
object Schedule {
    private val SLOTS = listOf(
        480 to 520,   // 08:00–08:40
        530 to 570,   // 08:50–09:30
        590 to 630,   // 09:50–10:30
        645 to 685,   // 10:45–11:25
        700 to 740,   // 11:40–12:20
        755 to 795,   // 12:35–13:15
        805 to 845,   // 13:25–14:05
        855 to 895    // 14:15–14:55
    )

    private fun L(i: Int, n: String, r: String = ""): Lesson {
        val t = SLOTS[i]
        return Lesson(n, r, t.first, t.second)
    }

    private fun F(n: String, r: String = "") = Lesson(n, r, -1, -1)

    private val map: Map<Int, List<Lesson>> = mapOf(
        Calendar.MONDAY to listOf(
            L(0, "Разговоры о важном", "305"),
            L(1, "Русский язык", "103"),
            L(2, "Геометрия", "203"),
            L(3, "Физика", "211"),
            L(4, "Обществознание", "306"),
            L(5, "ВиС", "203"),
            L(6, "Физра"),
            L(7, "История", "305")
        ),
        Calendar.TUESDAY to listOf(
            L(0, "Физика", "211"),
            L(1, "СкС", "307"),
            L(2, "Алгебра", "203"),
            L(3, "Алгебра", "203"),
            L(4, "ОБЗР", "406"),
            L(5, "Иняз", "403"),
            L(6, "ИКТ", "205")
        ),
        Calendar.WEDNESDAY to listOf(
            L(0, "Физика", "211"),
            L(1, "История", "305"),
            L(2, "Алгебра", "203"),
            L(3, "Алгебра", "203"),
            L(4, "Литература", "103"),
            L(5, "Литература", "103"),
            L(6, "Обществознание", "307")
        ),
        Calendar.THURSDAY to listOf(
            L(0, "Россия – мои горизонты", "305"),
            L(1, "Русский язык", "103"),
            L(2, "Геометрия", "203"),
            L(3, "Биология", "408"),
            L(4, "География", "204"),
            L(5, "Физика", "211"),
            L(6, "Иняз", "403"),
            F("ИП", "309"),
            F("Внеурочка", "211")
        ),
        Calendar.FRIDAY to listOf(
            L(0, "Физра"),
            L(1, "Физика", "211"),
            L(2, "Геометрия", "203"),
            L(3, "Литература", "103"),
            L(4, "Химия", "408"),
            L(5, "Иняз", "403")
        )
    )

    fun day(d: Int): List<Lesson> = map[d] ?: emptyList()
}

/* ================= ЦВЕТА ПРЕДМЕТОВ ================= */
object SubjectColors {
    fun of(name: String, accent: Int): Int {
        val n = name.lowercase(Locale("ru"))
        return when {
            n.contains("разговоры") -> 0xFFFF5A4E.toInt()
            n.contains("русский") -> 0xFF57A8FF.toInt()
            n.contains("литер") -> 0xFFFFB454.toInt()
            n.contains("алгебр") || n.contains("геометр") -> 0xFFA78BFA.toInt()
            n.contains("истор") || n.contains("общество") -> 0xFFFF8A3D.toInt()
            n.contains("физра") -> 0xFF6FE08C.toInt()
            n.contains("иняз") || n.contains("англ") -> 0xFF39D0C4.toInt()
            n.contains("физик") -> 0xFF60A5FA.toInt()
            n.contains("хими") -> 0xFFFACC15.toInt()
            n.contains("биоло") -> 0xFF4ADE80.toInt()
            n.contains("геогр") -> 0xFF8FD14F.toInt()
            n.contains("икт") || n.contains("информ") -> 0xFFF472B6.toInt()
            n.contains("горизонты") -> 0xFF57A8FF.toInt()
            else -> accent
        }
    }
}

/* ================= СОСТОЯНИЕ ================= */
class St {
    var phase = ""            // lesson | break | before | flex | after | empty
    var label = ""; var title = ""; var sub = ""
    var chipTime = ""; var chipRoom = ""
    var dot = 0
    var progPct = 0; var progColor = 0; var progText = ""
    var nextLabel = ""; var nextTitle = ""; var nextMeta = ""; var nextCount = ""
    var nextAlways = false
    var small = ""; var today = ""
    var live = false; var lessonIndex = -1
}

object Logic {
    fun fmt(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    fun human(mins: Int): String {
        var t = max(0, mins)
        val d = t / 1440; t %= 1440
        val h = t / 60; val mm = t % 60
        return when {
            d > 0 -> if (h == 0) "$d д" else "$d д $h ч"
            h > 0 -> if (mm == 0) "$h ч" else "$h ч $mm мин"
            else -> "$mm мин"
        }
    }

    private fun rp(room: String) = if (room.isEmpty()) "" else "каб. $room • "
    private fun meta(l: Lesson) = fmt(l.s) + if (l.room.isEmpty()) "" else " · каб. ${l.room}"

    private fun nextSchool(dow: Int): Triple<Int, List<Lesson>, List<Lesson>>? {
        for (i in 1..7) {
            val d = ((dow - 1 + i) % 7) + 1
            val a = Schedule.day(d)
            val t = a.filter { it.s >= 0 }; val f = a.filter { it.s < 0 }
            if (t.isNotEmpty() || f.isNotEmpty()) return Triple(i, t, f)
        }
        return null
    }

    private fun dayWord(offset: Int): String {
        if (offset == 1) return "завтра"
        val date = Date(System.currentTimeMillis() + offset * 86400000L)
        return "в " + SimpleDateFormat("EEEE", Locale("ru")).format(date)
    }

    private fun fillNext(st: St, offset: Int, timed: List<Lesson>, flex: List<Lesson>, cur: Int) {
        val date = Date(System.currentTimeMillis() + offset * 86400000L)
        val head = if (offset == 1) "ЗАВТРА"
        else SimpleDateFormat("EEE", Locale("ru")).format(date).uppercase(Locale("ru"))
        if (timed.isNotEmpty()) {
            val t = timed[0]
            st.nextLabel = "$head · ПЕРВЫЙ УРОК"
            st.nextTitle = t.name
            st.nextMeta = meta(t)
            st.nextCount = "через " + human(offset * 1440 + t.s - cur)
            st.nextAlways = true
        } else if (flex.isNotEmpty()) {
            st.nextLabel = "$head · ПОСЛЕ УРОКОВ"
            st.nextTitle = flex[0].name
            st.nextMeta = (if (flex[0].room.isEmpty()) "" else "каб. ${flex[0].room} · ") + "время неточное"
            st.nextCount = ""
            st.nextAlways = true
        }
    }

    fun calc(accent: Int): St {
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val sec = cur * 60 + c.get(Calendar.SECOND)
        val all = Schedule.day(dow)
        val list = all.filter { it.s >= 0 }
        val flex = all.filter { it.s < 0 }
        val st = St()
        st.dot = accent

        st.today = if (all.isEmpty()) "Сегодня уроков нет"
        else {
            var t = "Сегодня ${list.size} ур."
            if (list.isNotEmpty()) t += " • ${fmt(list.first().s)}–${fmt(list.last().e)}"
            if (flex.isNotEmpty()) t += " • после: " + flex.joinToString(", ") { it.name }
            t
        }

        /* выходной / уроков нет — пустое состояние как заголовок */
        if (list.isEmpty() && flex.isEmpty()) {
            st.phase = "empty"
            val wd = SimpleDateFormat("EEEE", Locale("ru")).format(Date()).uppercase(Locale("ru"))
            st.label = "СЕГОДНЯ · $wd"
            st.title = "Сегодня уроков нет"
            val nx = nextSchool(dow)
            if (nx != null) {
                st.sub = "Ближайшие уроки — ${dayWord(nx.first)}. Хорошего отдыха!"
                fillNext(st, nx.first, nx.second, nx.third, cur)
                st.small = "уроков нет • дальше ${dayWord(nx.first)}"
            } else { st.sub = "Расписание пустое."; st.small = "уроков нет" }
            return st
        }

        /* сейчас идёт урок */
        val nowL = list.firstOrNull { cur >= it.s && cur < it.e }
        if (nowL != null) {
            val idx = list.indexOf(nowL)
            st.phase = "lesson"; st.live = true; st.lessonIndex = idx
            st.label = "СЕЙЧАС · ${idx + 1} УРОК"
            st.dot = SubjectColors.of(nowL.name, accent)
            st.title = nowL.name
            st.chipTime = "${fmt(nowL.s)}–${fmt(nowL.e)}"
            st.chipRoom = if (nowL.room.isEmpty()) "" else "каб. ${nowL.room}"
            val left = human(ceil((nowL.e * 60 - sec) / 60.0).toInt())
            st.progPct = (((sec - nowL.s * 60).toFloat() / ((nowL.e - nowL.s) * 60)) * 100).toInt().coerceIn(0, 100)
            st.progColor = st.dot
            st.progText = "до конца $left"
            st.small = rp(nowL.room) + "до ${fmt(nowL.e)} • $left"
            val nx = list.firstOrNull { it.s >= nowL.e }
            when {
                nx != null -> {
                    st.nextLabel = "ДАЛЕЕ"; st.nextTitle = nx.name
                    st.nextMeta = meta(nx); st.nextCount = "через " + human(nx.s - cur)
                }
                flex.isNotEmpty() -> {
                    st.nextLabel = "ПОСЛЕ УРОКОВ"; st.nextTitle = flex[0].name
                    st.nextMeta = (if (flex[0].room.isEmpty()) "" else "каб. ${flex[0].room} · ") + "время неточное"
                }
                else -> {
                    val ns = nextSchool(dow)
                    if (ns != null) fillNext(st, ns.first, ns.second, ns.third, cur)
                    else { st.nextLabel = "ДАЛЕЕ"; st.nextTitle = "Последний урок сегодня" }
                }
            }
            return st
        }

        /* до первого урока */
        if (list.isNotEmpty() && cur < list.first().s) {
            val f = list.first()
            st.phase = "before"; st.lessonIndex = 0
            st.label = "ПЕРВЫЙ УРОК · СЕГОДНЯ"
            st.dot = SubjectColors.of(f.name, accent)
            st.title = f.name
            st.chipTime = fmt(f.s)
            st.chipRoom = if (f.room.isEmpty()) "" else "каб. ${f.room}"
            val d = f.s - cur
            st.progPct = if (d <= 60) ((60 - d) * 100 / 60).coerceIn(0, 100) else 0
            st.progColor = accent
            st.progText = "до начала " + human(d)
            st.small = rp(f.room) + "в ${fmt(f.s)} • через " + human(d)
            if (list.size > 1) {
                val n = list[1]
                st.nextLabel = "ДАЛЕЕ"; st.nextTitle = n.name
                st.nextMeta = meta(n); st.nextCount = "через " + human(n.s - cur)
            }
            return st
        }

        /* перемена */
        for (i in 0 until list.size - 1) {
            if (cur >= list[i].e && cur < list[i + 1].s) {
                val n = list[i + 1]
                st.phase = "break"; st.lessonIndex = i + 1
                st.label = "ПЕРЕМЕНА"
                st.dot = SubjectColors.of(n.name, accent)
                st.title = n.name
                st.chipTime = fmt(n.s)
                st.chipRoom = if (n.room.isEmpty()) "" else "каб. ${n.room}"
                st.progPct = (((cur - list[i].e).toFloat() / (n.s - list[i].e)) * 100).toInt().coerceIn(0, 100)
                st.progColor = accent
                st.progText = "до звонка " + human(n.s - cur)
                st.small = "перемена • звонок в ${fmt(n.s)} • " + human(n.s - cur)
                if (i + 2 < list.size) {
                    val t = list[i + 2]
                    st.nextLabel = "ЗАТЕМ"; st.nextTitle = t.name
                    st.nextMeta = meta(t); st.nextCount = "через " + human(t.s - cur)
                } else if (flex.isNotEmpty()) {
                    st.nextLabel = "ПОСЛЕ УРОКОВ"; st.nextTitle = flex[0].name
                    st.nextMeta = "время неточное"
                }
                return st
            }
        }

        /* после уроков: гибкие пары (ИП / Внеурочка) */
        if (flex.isNotEmpty()) {
            st.phase = "flex"
            st.label = "ПОСЛЕ УРОКОВ · СЕГОДНЯ"
            st.title = flex[0].name
            st.sub = "Время неточное — после основных уроков."
            st.chipRoom = if (flex[0].room.isEmpty()) "" else "каб. ${flex[0].room}"
            st.small = rp(flex[0].room) + "сегодня, после уроков"
            if (flex.size > 1) {
                st.nextLabel = "ЗАТЕМ"; st.nextTitle = flex[1].name
                st.nextMeta = if (flex[1].room.isEmpty()) "время неточное" else "каб. ${flex[1].room} · время неточное"
            } else {
                val ns = nextSchool(dow)
                if (ns != null) fillNext(st, ns.first, ns.second, ns.third, cur)
            }
            return st
        }

        /* учебный день завершён */
        st.phase = "after"
        st.dot = 0xFF6FE08C.toInt()
        st.label = "УЧЕБНЫЙ ДЕНЬ ЗАВЕРШЁН"
        st.title = "Уроки закончились"
        st.sub = if (list.isNotEmpty()) "Сегодня ${list.size} уроков • ${fmt(list.first().s)}–${fmt(list.last().e)}. Свободное время!" else "Свободное время!"
        val ns = nextSchool(dow)
        if (ns != null) {
            fillNext(st, ns.first, ns.second, ns.third, cur)
            st.small = "завтра в ${fmt(ns.second.firstOrNull()?.s ?: 480)} • через " + human(ns.first * 1440 + (ns.second.firstOrNull()?.s ?: 480) - cur)
        } else st.small = "уроков нет"
        return st
    }
}

/* ================= НАСТРОЙКИ (старые ключи сохранены) ================= */
class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var c1: Int get() = sp.getInt("c1", 0xFF18322B.toInt()); set(v) { sp.edit().putInt("c1", v).apply() }
    var c2: Int get() = sp.getInt("c2", 0xFF0B1915.toInt()); set(v) { sp.edit().putInt("c2", v).apply() }
    var radius: Int get() = sp.getInt("radius", 26); set(v) { sp.edit().putInt("radius", v).apply() }
    var angle: Int get() = sp.getInt("angle", 135); set(v) { sp.edit().putInt("angle", v).apply() }
    var text: Int get() = sp.getInt("text", Color.WHITE); set(v) { sp.edit().putInt("text", v).apply() }
    var op: Int get() = sp.getInt("op", 100); set(v) { sp.edit().putInt("op", v).apply() }
    var accent: Int get() = sp.getInt("accent", 0xFFFF5A4E.toInt()); set(v) { sp.edit().putInt("accent", v).apply() }
    var theme: Int get() = sp.getInt("theme", 0); set(v) { sp.edit().putInt("theme", v).apply() } // 0 авто, 1 тёмная, 2 светлая
    var showNext: Boolean get() = sp.getBoolean("showNext", true); set(v) { sp.edit().putBoolean("showNext", v).apply() }
    var countdown: Boolean get() = sp.getBoolean("countdown", true); set(v) { sp.edit().putBoolean("countdown", v).apply() }
    var progress: Boolean get() = sp.getBoolean("progress", true); set(v) { sp.edit().putBoolean("progress", v).apply() }
    var font: Int get() = sp.getInt("font", 100); set(v) { sp.edit().putInt("font", v).apply() }
}

fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

/* ================= ОТРИСОВКА ВИДЖЕТА ================= */
object Art {
    private fun lum(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
    fun isLight(p: Prefs) = when (p.theme) { 2 -> true; 1 -> false; else -> (lum(p.c1) + lum(p.c2)) / 2f > 0.62f }

    private fun font(p: Paint, size: Float, bold: Boolean, color: Int, ls: Float = 0f) {
        p.textSize = size; p.color = color; p.letterSpacing = ls
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textAlign = Paint.Align.LEFT
    }

    private fun block(cv: Canvas, s: String, tp: TextPaint, x: Float, y: Float, w: Int, maxLines: Int): Float {
        if (s.isEmpty() || w <= 0) return 0f
        val sl = StaticLayout.Builder.obtain(s, 0, s.length, tp, w)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
        cv.save(); cv.translate(x, y); sl.draw(cv); cv.restore()
        return sl.height.toFloat()
    }

    private fun chip(cv: Canvas, p: Paint, x: Float, y: Float, h: Float, txt: String, bg: Int, fg: Int, d: Float, fs: Float): Float {
        font(p, 10.5f * d * fs, true, fg)
        val w = p.measureText(txt) + 18f * d
        p.color = bg; cv.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, p)
        p.color = fg; cv.drawText(txt, x + 9f * d, y + h / 2 - (p.ascent() + p.descent()) / 2, p)
        return w
    }

    fun widget(w: Int, h: Int, p: Prefs, st: St, big: Boolean, d: Float): Bitmap {
        val bmp = Bitmap.createBitmap(max(4, w), max(4, h), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val light = isLight(p)
        val op = p.op.coerceIn(20, 100)
        val fs = p.font.coerceIn(90, 115) / 100f
        val ink = if (light) 0xFF16241E.toInt() else 0xFFF0F7F2.toInt()
        val mut = if (light) 0xFF5B6F66.toInt() else 0xFFA9C2B5.toInt()
        val chipBg = if (light) 0x14142822 else 0x17FFFFFF
        val line = if (light) 0x1F142822 else 0x1AFFFFFF
        val inkA = alpha(ink, max(150, 255 * op / 100))
        val mutA = alpha(mut, max(140, 215 * op / 100))
        val accA = alpha(p.accent, max(160, 255 * op / 100))
        val r = p.radius * d
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())

        /* фон: градиент + блик + скрим для контраста на любых обоях */
        val rad = Math.toRadians(p.angle.toDouble())
        val cx = w / 2f; val cy = h / 2f
        val dx = (cos(rad) * w / 2).toFloat(); val dy = (sin(rad) * h / 2).toFloat()
        paint.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
            alpha(p.c1, op * 255 / 100), alpha(p.c2, op * 255 / 100), Shader.TileMode.CLAMP)
        cv.drawRoundRect(rect, r, r, paint)
        paint.shader = RadialGradient(w * 0.18f, h * 0.08f, max(w, h) * 0.95f,
            Color.argb(40 * op / 100, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        if (op < 70) {
            paint.color = if (light) Color.argb((70 - op) * 3, 255, 255, 255) else Color.argb((70 - op) * 3, 0, 0, 0)
            cv.drawRoundRect(rect, r, r, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = d
        paint.color = if (light) 0x14142822 else 0x14FFFFFF
        cv.drawRoundRect(RectF(d / 2, d / 2, w - d / 2, h - d / 2), r, r, paint)
        paint.style = Paint.Style.FILL

        val pad = (if (big) 14f else 10f) * d

        /* ---------- МАЛЫЙ ВИДЖЕТ ---------- */
        if (!big) {
            val tiny = h < 52 * d
            var y = if (tiny) 6f * d else pad
            if (!tiny) {
                paint.color = st.dot
                cv.drawCircle(pad + 3.5f * d, y + 5f * d, 3.5f * d, paint)
                font(paint, 8.5f * d * fs, true, mutA, 0.1f)
                cv.drawText(st.label, pad + 12f * d, y + 8.5f * d, paint)
                y += 14f * d
            }
            font(tp, 14f * d * fs, true, inkA)
            y += block(cv, st.title, tp, pad, y, (w - 2 * pad).toInt(), 1)
            font(tp, 9.5f * d * fs, false, mutA)
            block(cv, st.small, tp, pad, y + 1f * d, (w - 2 * pad).toInt(), 1)
            if (p.progress && h >= 60 * d && st.progText.isNotEmpty()) {
                val bh = 3f * d; val top = h - 6f * d - bh
                paint.color = chipBg; cv.drawRoundRect(RectF(pad, top, w - pad, top + bh), bh / 2, bh / 2, paint)
                paint.color = alpha(st.progColor, 230)
                cv.drawRoundRect(RectF(pad, top, pad + (w - 2 * pad) * st.progPct / 100f, top + bh), bh / 2, bh / 2, paint)
            }
            return bmp
        }

        /* ---------- БОЛЬШОЙ ВИДЖЕТ ---------- */
        val tiny = h < 150 * d
        val compact = h < 215 * d
        var y = pad

        /* шапка: точка + статус + обновление */
        val dotR = 4f * d
        val dcx = pad + dotR; val dcy = y + 8f * d
        paint.shader = RadialGradient(dcx, dcy, 10f * d, alpha(st.dot, 90), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawCircle(dcx, dcy, 10f * d, paint); paint.shader = null
        paint.color = st.dot; cv.drawCircle(dcx, dcy, dotR, paint)
        font(paint, 9.5f * d * fs, true, mutA, 0.12f)
        cv.drawText(st.label, pad + 14f * d, y + 12f * d, paint)
        val rr = 6.5f * d; val rcx = w - pad - rr; val rcy = y + 8f * d
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * d; paint.strokeCap = Paint.Cap.ROUND
        paint.color = mutA
        cv.drawArc(RectF(rcx - rr, rcy - rr, rcx + rr, rcy + rr), -60f, 300f, false, paint)
        paint.style = Paint.Style.FILL
        val ax = rcx + rr * 0.5f; val ay = rcy - rr * 0.866f
        val path = Path(); path.moveTo(ax - 3f * d, ay - 1f * d); path.lineTo(ax + 3.2f * d, ay - 2f * d); path.lineTo(ax + 0.5f * d, ay + 3.5f * d); path.close()
        cv.drawPath(path, paint)
        y += 22f * d

        /* заголовок */
        val tSize = (if (w < 170 * d) 17f else 20f) * d * fs
        font(tp, tSize, true, inkA)
        val tLines = if (tiny || (compact && h < 175 * d)) 1 else 2
        y += block(cv, st.title, tp, pad, y, (w - 2 * pad).toInt(), tLines)

        /* подзаголовок (пустые состояния) */
        if (st.sub.isNotEmpty() && !tiny) {
            font(tp, 11.5f * d * fs, false, mutA)
            y += block(cv, st.sub, tp, pad, y + 3f * d, (w - 2 * pad).toInt(), if (compact) 1 else 2) + 3f * d
        }

        /* чипы: время и кабинет */
        if (st.chipTime.isNotEmpty() && !tiny) {
            y += 8f * d
            val ch = 22f * d
            var x = pad
            x += chip(cv, paint, x, y, ch, st.chipTime, chipBg, inkA, d, fs) + 6f * d
            if (st.chipRoom.isNotEmpty() && x < w - pad - 40f * d)
                chip(cv, paint, x, y, ch, st.chipRoom, chipBg, inkA, d, fs)
            y += ch
        }

        /* прогресс + отсчёт */
        val showProg = p.progress && st.progText.isNotEmpty() && !tiny &&
            (st.phase == "lesson" || st.phase == "break" || st.phase == "before")
        if (showProg) {
            y += 12f * d
            font(paint, 10f * d * fs, true, mutA)
            val cap = st.progText
            val cw = paint.measureText(cap)
            val bh = 6f * d
            val bw = w - 2 * pad - cw - 10f * d
            val top = y - bh / 2 - 3f * d
            paint.color = chipBg; cv.drawRoundRect(RectF(pad, top, pad + bw, top + bh), bh / 2, bh / 2, paint)
            paint.color = alpha(st.progColor, max(160, 255 * op / 100))
            cv.drawRoundRect(RectF(pad, top, pad + max(bh, bw * st.progPct / 100f), top + bh), bh / 2, bh / 2, paint)
            cv.drawText(cap, w - pad - cw, y, paint)
            y += 8f * d
        }

        /* блок «Далее / Завтра» — прижат к низу */
        val showNext = st.nextTitle.isNotEmpty() && (st.nextAlways || p.showNext) && !tiny
        if (showNext) {
            if (!compact) {
                val metaY = h - pad - 2f * d
                val titleY = metaY - 16f * d
                val labY = titleY - 20f * d
                val divY = labY - 14f * d
                paint.color = line; cv.drawRect(pad, divY, w - pad, divY + d, paint)
                font(paint, 8.5f * d * fs, true, accA, 0.13f)
                cv.drawText(st.nextLabel, pad, labY, paint)
                if (st.nextCount.isNotEmpty()) {
                    font(paint, 9.5f * d * fs, false, mutA)
                    paint.textAlign = Paint.Align.RIGHT
                    cv.drawText(st.nextCount, w - pad, labY, paint)
                    paint.textAlign = Paint.Align.LEFT
                }
                font(tp, 13.5f * d * fs, true, inkA)
                block(cv, st.nextTitle, tp, pad, titleY - 13f * d, (w - 2 * pad).toInt(), 1)
                font(tp, 10.5f * d * fs, false, mutA)
                block(cv, st.nextMeta, tp, pad, metaY - 10.5f * d, (w - 2 * pad).toInt(), 1)
            } else {
                val divY = h - pad - 18f * d
                paint.color = line; cv.drawRect(pad, divY, w - pad, divY + d, paint)
                font(tp, 10.5f * d * fs, false, mutA)
                val one = st.nextLabel.lowercase(Locale("ru")).replaceFirstChar { it.uppercase() } +
                    ": " + st.nextTitle + (if (st.nextMeta.isNotEmpty()) " • " + st.nextMeta else "")
                block(cv, one, tp, pad, divY + 5f * d, (w - 2 * pad).toInt(), 1)
            }
        }
        return bmp
    }
}

/* ================= ОБНОВЛЕНИЕ ВИДЖЕТОВ ================= */
fun render(ctx: Context, mgr: AppWidgetManager, id: Int, big: Boolean) {
    val p = Prefs(ctx)
    val st = Logic.calc(p.accent)
    val o = mgr.getAppWidgetOptions(id)
    val dm = ctx.resources.displayMetrics
    var wd = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    var hd = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
    if (wd <= 0) wd = if (big) 180 else 160
    if (hd <= 0) hd = if (big) 200 else 68
    val w = min(720, max(64, (wd * dm.density).toInt()))
    val h = min(720, max(48, (hd * dm.density).toInt()))

    val rv = RemoteViews(ctx.packageName, if (big) R.layout.w_big else R.layout.w_small)
    rv.setImageViewBitmap(R.id.bg, Art.widget(w, h, p, st, big, dm.density))
    rv.setViewVisibility(R.id.hint, View.GONE)

    val open = PendingIntent.getActivity(ctx, if (big) 10 else 11,
        Intent(ctx, MainActivity::class.java).putExtra("tab", 1),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    rv.setOnClickPendingIntent(R.id.root, open)

    if (big) {
        val ref = PendingIntent.getBroadcast(ctx, 12,
            Intent(ctx, BigWidget::class.java).setAction(Updater.REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        rv.setOnClickPendingIntent(R.id.refresh, ref)
    }
    mgr.updateAppWidget(id, rv)
}

object Updater {
    const val TICK = "com.school.widget.TICK"
    const val REFRESH = "com.school.widget.REFRESH"

    fun all(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        mgr.getAppWidgetIds(ComponentName(ctx, SmallWidget::class.java)).forEach { render(ctx, mgr, it, false) }
        mgr.getAppWidgetIds(ComponentName(ctx, BigWidget::class.java)).forEach { render(ctx, mgr, it, true) }
        schedule(ctx)
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 1,
            Intent(ctx, SmallWidget::class.java).setAction(TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + (60000L - System.currentTimeMillis() % 60000L) + 250L
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
            catch (e2: Exception) { am.set(AlarmManager.RTC_WAKEUP, at, pi) }
        }
    }
}

class SmallWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(c, m, it, false) }; Updater.schedule(c)
    }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id, false)
    override fun onEnabled(c: Context) = Updater.schedule(c)
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        when (i.action) {
            Updater.TICK, Updater.REFRESH, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED, Intent.ACTION_USER_PRESENT -> Updater.all(c)
        }
    }
}

class BigWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(c, m, it, true) }; Updater.schedule(c)
    }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = render(c, m, id, true)
    override fun onEnabled(c: Context) = Updater.schedule(c)
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        when (i.action) {
            Updater.TICK -> Updater.all(c)
            Updater.REFRESH -> {
                Updater.all(c)
                Toast.makeText(c, "Расписание обновлено", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/* ================= ПРИЛОЖЕНИЕ ================= */
class MainActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var content: LinearLayout
    private lateinit var tabRow: LinearLayout
    private var preview: ImageView? = null
    private var tab = 0
    private var weekDay = 0
    private var lastMin = -1
    private val h = Handler(Looper.getMainLooper())
    private val apply = Runnable { Updater.all(this) }
    private val tick = object : Runnable {
        override fun run() {
            if (tab != 1) drawPreview()
            val m = Calendar.getInstance().get(Calendar.MINUTE)
            if (tab == 1 && m != lastMin) { lastMin = m; buildWeek() }
            h.postDelayed(this, 1000)
        }
    }

    private val cBg = 0xFF0B1D19.toInt()
    private val cSurf = 0xFF12302A.toInt()
    private val cSurf2 = 0xFF173A32.toInt()
    private val cInk = 0xFFECF6F0.toInt()
    private val cMut = 0xFF9FB9AD.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun round(color: Int, r: Float, stroke: Int = 0, sw: Int = 0): GradientDrawable {
        val g = GradientDrawable(); g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = r; g.setColor(color)
        if (sw > 0) g.setStroke(sw, stroke)
        return g
    }

    private fun tv(t: String, size: Float, bold: Boolean, color: Int = cInk): TextView {
        val v = TextView(this)
        v.text = t; v.textSize = size; v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        v.setPadding(0, dp(6), 0, dp(2))
        return v
    }

    private fun btn(t: String, act: () -> Unit): Button {
        val b = Button(this)
        b.text = t; b.isAllCaps = false; b.textSize = 13f
        b.setTextColor(cInk); b.background = round(cSurf2, dpf(13f), 0x2A9FB9AD, dp(1))
        b.stateListAnimator = null
        b.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); act() }
        return b
    }

    private fun debounce() { h.removeCallbacks(apply); h.postDelayed(apply, 350) }

    private fun drawPreview() {
        val pv = preview ?: return
        pv.setImageBitmap(Art.widget(720, 620, p, Logic.calc(p.accent), true, 3f))
    }

    /* ---------- вкладки ---------- */
    private fun selectTab(i: Int) {
        tab = i; preview = null
        for (k in 0 until tabRow.childCount) {
            val b = tabRow.getChildAt(k) as Button
            if (k == i) { b.background = round(p.accent, dpf(12f)); b.setTextColor(0xFF2B0B08.toInt()) }
            else { b.background = round(cSurf, dpf(12f)); b.setTextColor(cMut) }
        }
        content.removeAllViews()
        when (i) { 0 -> buildToday(); 1 -> buildWeek(); else -> buildStyle() }
    }

    private fun buildToday() {
        content.addView(tv("Так виджет выглядит на рабочем столе", 13f, false, cMut))
        val pv = ImageView(this); pv.scaleType = ImageView.ScaleType.FIT_CENTER
        preview = pv
        content.addView(pv, LinearLayout.LayoutParams(-1, dp(300)).apply { topMargin = dp(8) })
        drawPreview()
        content.addView(tv("Предпросмотр живой: прогресс и отсчёт обновляются каждую секунду. Тап по виджету на рабочем столе открывает вкладку «Неделя», кнопка ⟳ сверху справа — обновляет данные.", 12f, false, cMut))
        content.addView(btn("Обновить виджеты сейчас") { Updater.all(this); Toast.makeText(this, "Виджеты обновлены", Toast.LENGTH_SHORT).show() })
        if (Build.VERSION.SDK_INT >= 31) content.addView(btn("Разрешить точные будильники") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        })
        content.addView(btn("Настройки приложения (автозапуск)") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
        })
        content.addView(tv("Работает офлайн. Добавь виджет: зажми рабочий стол → Виджеты → Расписание 2×1 или 2×2. Большой виджет можно растянуть на 3 ряда — появится блок «Далее».", 12f, false, cMut))
    }

    private fun buildWeek() {
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this); hs.addView(row)
        content.addView(hs)
        for (i in 0..6) {
            val b = btn(names[i]) { weekDay = i; buildWeek() }
            if (i == weekDay) { b.background = round(p.accent, dpf(12f)); b.setTextColor(0xFF2B0B08.toInt()) }
            row.addView(b, LinearLayout.LayoutParams(dp(56), -2).apply { marginEnd = dp(6) })
        }
        val dow = (weekDay + 1) % 7 + 1
        val all = Schedule.day(dow)
        val timed = all.filter { it.s >= 0 }
        val flex = all.filter { it.s < 0 }
        val todayIdx = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7
        val full = SimpleDateFormat("EEEE", Locale("ru"))
            .format(Date(System.currentTimeMillis() + (weekDay - todayIdx) * 86400000L))
            .replaceFirstChar { it.uppercase() }
        content.addView(tv(full, 18f, true))
        content.addView(tv(if (timed.isEmpty() && flex.isEmpty()) "Выходной — уроков нет"
        else "${timed.size} уроков" + (if (timed.isNotEmpty()) " • ${Logic.fmt(timed.first().s)}–${Logic.fmt(timed.last().e)}" else "") +
            (if (flex.isNotEmpty()) " • после: " + flex.joinToString(", ") { it.name } else ""), 12f, false, cMut))

        val st = Logic.calc(p.accent)
        val isToday = weekDay == todayIdx

        if (timed.isEmpty() && flex.isEmpty()) {
            content.addView(tv("Ближайшие уроки — в понедельник, 08:00. Хорошего отдыха!", 13f, false, cMut))
            return
        }
        timed.forEachIndexed { i, l ->
            val now = isToday && st.phase == "lesson" && st.lessonIndex == i
            val next = isToday && ((st.phase == "break" && st.lessonIndex == i) ||
                (st.phase == "before" && i == 0) || (st.phase == "lesson" && st.lessonIndex == i - 1))
            content.addView(lessonRow(l, Logic.fmt(l.s), Logic.fmt(l.e), now, next))
            if (i < timed.size - 1) {
                val gap = timed[i + 1].s - l.e
                val bNow = isToday && st.phase == "break" && st.lessonIndex == i + 1
                val t = tv("перемена · $gap мин" + if (bNow) " · сейчас" else "", 10f, false, if (bNow) 0xFFFFB454.toInt() else 0xFF7E988C.toInt())
                t.setPadding(dp(60), dp(2), 0, dp(2))
                content.addView(t)
            }
        }
        flex.forEach { content.addView(lessonRow(it, "после", "уроков", false, false)) }
    }

    private fun lessonRow(l: Lesson, t1: String, t2: String, now: Boolean, next: Boolean): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(10), dp(9), dp(10), dp(9))
        row.background = round(if (now) alpha(p.accent, 30) else cSurf, dpf(14f), if (now) alpha(p.accent, 110) else 0, if (now) dp(1) else 0)
        val time = tv("$t1\n$t2", 11f, true, cMut); time.width = dp(48)
        row.addView(time)
        val dot = View(this); dot.background = round(SubjectColors.of(l.name, p.accent), dpf(5f))
        row.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(tv(l.name, 14f, true))
        col.addView(tv(if (l.room.isEmpty()) "без кабинета" else "каб. ${l.room}", 11f, false, cMut))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (now || next) {
            val tag = tv(if (now) "ИДЁТ" else "ДАЛЕЕ", 9f, true, if (now) alpha(p.accent, 255) else 0xFF8EC3FF.toInt())
            tag.background = round(if (now) alpha(p.accent, 45) else 0x2957A8FF, dpf(20f))
            tag.setPadding(dp(8), dp(3), dp(8), dp(3))
            row.addView(tag)
        }
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        return row
    }

    /* ---------- стиль ---------- */
    private fun buildStyle() {
        val pv = ImageView(this); pv.scaleType = ImageView.ScaleType.FIT_CENTER
        preview = pv
        content.addView(pv, LinearLayout.LayoutParams(-1, dp(170)).apply { bottomMargin = dp(6) })
        drawPreview()

        content.addView(tv("Пресеты", 15f, true))
        val hs = HorizontalScrollView(this); val row = LinearLayout(this); hs.addView(row); content.addView(hs)
        val presets = listOf(
            Triple("Маки", 0xFF18322B.toInt(), 0xFF0B1915.toInt()),
            Triple("Индиго", 0xFF4F46E5.toInt(), 0xFF06B6D4.toInt()),
            Triple("Закат", 0xFFF97316.toInt(), 0xFFDB2777.toInt()),
            Triple("Мята", 0xFF10B981.toInt(), 0xFF0EA5E9.toInt()),
            Triple("Океан", 0xFF0EA5E9.toInt(), 0xFF1E3A8A.toInt()),
            Triple("Аметист", 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt()),
            Triple("Графит", 0xFF334155.toInt(), 0xFF0F172A.toInt()),
            Triple("Светлый", 0xFFFBFDFC.toInt(), 0xFFE8F1EC.toInt())
        )
        for (t in presets) row.addView(btn(t.first) {
            p.c1 = t.second; p.c2 = t.third; drawPreview(); debounce()
        })

        content.addView(tv("Акцент", 15f, true))
        val ar = LinearLayout(this); content.addView(ar)
        val accents = listOf(0xFFFF5A4E.toInt(), 0xFFFFB454.toInt(), 0xFF57A8FF.toInt(), 0xFF6FE08C.toInt(), 0xFFA78BFA.toInt(), 0xFF39D0C4.toInt())
        for (c in accents) {
            val v = View(this)
            v.background = round(c, dpf(15f), if (c == p.accent) Color.WHITE else 0, dp(2))
            v.setOnClickListener { p.accent = c; drawPreview(); debounce(); selectTab(2) }
            ar.addView(v, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        }

        content.addView(tv("Тема текста", 15f, true))
        val tr = LinearLayout(this); content.addView(tr)
        listOf("Авто", "Тёмная", "Светлая").forEachIndexed { i, t ->
            val b = btn(t) { p.theme = i; drawPreview(); debounce(); selectTab(2) }
            if (p.theme == i) { b.background = round(p.accent, dpf(12f)); b.setTextColor(0xFF2B0B08.toInt()) }
            tr.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        }

        content.addView(tv("Поведение", 15f, true))
        fun switchRow(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val r = LinearLayout(this); r.gravity = Gravity.CENTER_VERTICAL
            r.addView(tv(title, 14f, false), LinearLayout.LayoutParams(0, -2, 1f))
            val s = Switch(this); s.isChecked = get()
            s.setOnCheckedChangeListener { _, v -> set(v); drawPreview(); debounce() }
            r.addView(s); content.addView(r)
        }
        switchRow("Строка «Далее»", { p.showNext }, { p.showNext = it })
        switchRow("Обратный отсчёт", { p.countdown }, { p.countdown = it })
        switchRow("Шкала прогресса", { p.progress }, { p.progress = it })

        slider("Закругление углов", 44, { p.radius - 6 }, { p.radius = it + 6 })
        slider("Угол градиента", 360, { p.angle }, { p.angle = it })
        slider("Прозрачность (20–100 %)", 80, { p.op - 20 }, { p.op = it + 20 })
        slider("Размер текста (90–115 %)", 25, { p.font - 90 }, { p.font = it + 90 })

        content.addView(picker("Цвет градиента 1", { p.c1 }, { p.c1 = it }))
        content.addView(picker("Цвет градиента 2", { p.c2 }, { p.c2 = it }))

        content.addView(btn("Сброс стиля") {
            p.c1 = 0xFF18322B.toInt(); p.c2 = 0xFF0B1915.toInt(); p.accent = 0xFFFF5A4E.toInt()
            p.radius = 26; p.angle = 135; p.op = 100; p.theme = 0; p.font = 100
            p.showNext = true; p.countdown = true; p.progress = true
            debounce(); selectTab(2)
        })
    }

    private fun slider(title: String, max: Int, get: () -> Int, set: (Int) -> Unit) {
        content.addView(tv(title, 15f, true))
        val sb = SeekBar(this); sb.max = max; sb.progress = get()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { set(v); drawPreview(); debounce() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        content.addView(sb)
    }

    private fun picker(title: String, get: () -> Int, set: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16), 0, 0)
        val head = LinearLayout(this); head.gravity = Gravity.CENTER_VERTICAL
        val hex = tv(String.format("#%06X", 0xFFFFFF and get()), 13f, false, cMut)
        val sw = View(this); sw.background = round(get(), dpf(6f))
        head.addView(tv(title, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(hex); head.addView(sw, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(10) })
        box.addView(head)
        val hsv = FloatArray(3); Color.colorToHSV(get(), hsv)
        fun upd() {
            val c = Color.HSVToColor(hsv); set(c)
            sw.background = round(c, dpf(6f))
            hex.text = String.format("#%06X", 0xFFFFFF and c)
            drawPreview(); debounce()
        }
        val names = listOf("Оттенок", "Насыщенность", "Яркость")
        val maxs = listOf(360, 100, 100)
        for (i in 0..2) {
            box.addView(tv(names[i], 11f, false, cMut))
            val sb = SeekBar(this); sb.max = maxs[i]
            sb.progress = when (i) { 0 -> hsv[0].toInt(); 1 -> (hsv[1] * 100).toInt(); else -> (hsv[2] * 100).toInt() }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {
                    when (i) { 0 -> hsv[0] = v.toFloat(); 1 -> hsv[1] = v / 100f; else -> hsv[2] = v / 100f }
                    upd()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            box.addView(sb)
        }
        return box
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = Prefs(this)
        window.setBackgroundDrawable(ColorDrawable(cBg))
        weekDay = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(22), dp(18), dp(10))
        root.addView(tv("Расписание", 24f, true))
        root.addView(tv("Виджет школьного расписания · офлайн", 12f, false, cMut))

        tabRow = LinearLayout(this); tabRow.setPadding(0, dp(12), 0, dp(12))
        listOf("Сегодня", "Неделя", "Стиль").forEachIndexed { i, t ->
            val b = btn(t) { selectTab(i) }
            tabRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (i < 2) dp(6) else 0 })
        }
        root.addView(tabRow)

        val sc = ScrollView(this)
        content = LinearLayout(this); content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, 0, 0, dp(30))
        sc.addView(content)
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        selectTab(intent.getIntExtra("tab", 0).coerceIn(0, 2))
        Updater.schedule(this)
    }

    override fun onResume() {
        super.onResume(); Updater.all(this)
        h.removeCallbacks(tick); h.post(tick)
        if (tab == 1) buildWeek()
    }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}
// КОНЕЦ ФАЙЛА