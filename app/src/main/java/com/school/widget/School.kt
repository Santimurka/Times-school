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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
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

object Schedule {
    private val SLOTS = listOf(
        480 to 520, 530 to 570, 590 to 630, 645 to 685,
        700 to 740, 755 to 795, 805 to 845, 865 to 905
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

class St {
    var label = ""; var title = ""; var sub = ""; var badge = ""
    var next = ""; var today = ""; var room = ""; var progress = 0; var live = false
}

object Logic {
    fun fmt(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    fun human(mins: Int): String {
        var t = max(0, mins)
        val d = t / 1440; t %= 1440
        val h = t / 60; val mm = t % 60
        return when { d > 0 -> "$d д $h ч"; h > 0 -> "$h ч $mm мин"; else -> "$mm мин" }
    }

    private fun roomIn(l: Lesson) = if (l.room.isEmpty()) "" else " (каб. ${l.room})"

    fun calc(): St {
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val cur = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val sec = cur * 60 + c.get(Calendar.SECOND)
        val all = Schedule.day(dow)
        val list = all.filter { it.s >= 0 }
        val flex = all.filter { it.s < 0 }
        val st = St()

        st.today = if (all.isEmpty()) "Сегодня уроков нет"
        else {
            var t = "Сегодня ${list.size} ур."
            if (list.isNotEmpty()) t += " • ${fmt(list.first().s)}–${fmt(list.last().e)}"
            if (flex.isNotEmpty()) t += " • после: " + flex.joinToString(", ") { it.name }
            t
        }

        val nowL = list.firstOrNull { cur >= it.s && cur < it.e }
        if (nowL != null) {
            st.live = true
            st.label = "СЕЙЧАС ИДЁТ"
            st.room = nowL.room
            st.title = nowL.name
            st.sub = "${fmt(nowL.s)}–${fmt(nowL.e)}"
            st.badge = "осталось " + human(ceil((nowL.e * 60 - sec) / 60.0).toInt())
            st.progress = (((sec - nowL.s * 60).toFloat() / ((nowL.e - nowL.s) * 60)) * 100)
                .toInt().coerceIn(0, 100)
            val nx = list.firstOrNull { it.s >= nowL.e }
            st.next = when {
                nx != null -> "Дальше: ${nx.name}" + roomIn(nx) + " в ${fmt(nx.s)}"
                flex.isNotEmpty() -> "После уроков: ${flex[0].name}" + roomIn(flex[0])
                else -> "Последний урок сегодня"
            }
            return st
        }

        var target = list.firstOrNull { it.s > cur }
        var offset = 0
        var flexTarget: Lesson? = null
        var dayTimed = list
        var dayFlex = flex

        if (target == null) {
            if (flex.isNotEmpty()) {
                flexTarget = flex[0]
            } else {
                for (i in 1..7) {
                    val d = ((dow - 1 + i) % 7) + 1
                    val tl = Schedule.day(d).filter { it.s >= 0 }
                    val fl = Schedule.day(d).filter { it.s < 0 }
                    if (tl.isNotEmpty()) { target = tl[0]; offset = i; dayTimed = tl; dayFlex = fl; break }
                    if (fl.isNotEmpty()) { flexTarget = fl[0]; offset = i; dayTimed = tl; dayFlex = fl; break }
                }
            }
        }

        if (target == null && flexTarget == null) {
            st.label = "РАСПИСАНИЕ"; st.title = "Уроков нет"; return st
        }

        val dayTxt = when (offset) {
            0 -> "сегодня"
            1 -> "завтра"
            else -> SimpleDateFormat("EEE, d MMM", Locale("ru"))
                .format(Date(System.currentTimeMillis() + offset * 86400000L))
        }

        if (flexTarget != null) {
            st.label = "СЛЕДУЮЩИЙ УРОК"
            st.room = flexTarget.room
            st.title = flexTarget.name
            st.sub = "$dayTxt • время неточное"
            st.badge = "после уроков"
            st.next = if (dayFlex.size > 1) "Дальше: ${dayFlex[1].name}" + roomIn(dayFlex[1])
            else "Последнее на этот день"
            return st
        }

        val t2 = target!!
        st.label = "СЛЕДУЮЩИЙ УРОК"
        st.room = t2.room
        st.title = t2.name
        st.sub = "$dayTxt в ${fmt(t2.s)}"
        st.badge = "через " + human(offset * 1440 + t2.s - cur)
        val after = dayTimed.firstOrNull { it.s >= t2.e }
        st.next = when {
            after != null -> "Дальше: ${after.name}" + roomIn(after) + " в ${fmt(after.s)}"
            dayFlex.isNotEmpty() -> "После уроков: ${dayFlex[0].name}" + roomIn(dayFlex[0])
            else -> "Последний урок в этот день"
        }
        return st
    }
}

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    var c1: Int get() = sp.getInt("c1", 0xFF4F46E5.toInt()); set(v) { sp.edit().putInt("c1", v).apply() }
    var c2: Int get() = sp.getInt("c2", 0xFF06B6D4.toInt()); set(v) { sp.edit().putInt("c2", v).apply() }
    var radius: Int get() = sp.getInt("radius", 26); set(v) { sp.edit().putInt("radius", v).apply() }
    var angle: Int get() = sp.getInt("angle", 135); set(v) { sp.edit().putInt("angle", v).apply() }
    var text: Int get() = sp.getInt("text", Color.WHITE); set(v) { sp.edit().putInt("text", v).apply() }
}

object Art {
    fun bg(w: Int, h: Int, r: Float, c1: Int, c2: Int, angle: Int, prog: Int, bar: Boolean, acc: Int): Bitmap {
        val bmp = Bitmap.createBitmap(max(4, w), max(4, h), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val rad = Math.toRadians(angle.toDouble())
        val cx = w / 2f; val cy = h / 2f
        val dx = (cos(rad) * w / 2).toFloat(); val dy = (sin(rad) * h / 2).toFloat()
        p.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, c1, c2, Shader.TileMode.CLAMP)
        cv.drawRoundRect(rect, r, r, p)
        p.shader = RadialGradient(w * 0.18f, h * 0.08f, max(w, h) * 0.95f,
            Color.argb(55, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRoundRect(rect, r, r, p)
        p.shader = null
        if (bar) {
            val pad = w * 0.07f
            val bh = max(4f, h * 0.032f)
            val top = h - pad * 0.7f - bh
            p.color = Color.argb(60, 255, 255, 255)
            cv.drawRoundRect(RectF(pad, top, w - pad, top + bh), bh / 2, bh / 2, p)
            p.color = acc
            val right = pad + (w - 2 * pad) * prog / 100f
            cv.drawRoundRect(RectF(pad, top, max(right, pad + bh), top + bh), bh / 2, bh / 2, p)
        }
        return bmp
    }
}

fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

fun render(ctx: Context, mgr: AppWidgetManager, id: Int, big: Boolean) {
    val p = Prefs(ctx)
    val st = Logic.calc()
    val o = mgr.getAppWidgetOptions(id)
    val dm = ctx.resources.displayMetrics
    var wd = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
    var hd = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
    if (wd <= 0) wd = 160
    if (hd <= 0) hd = if (big) 160 else 68
    val w = min(720, max(64, (wd * dm.density).toInt()))
    val h = min(720, max(48, (hd * dm.density).toInt()))

    val rv = RemoteViews(ctx.packageName, if (big) R.layout.w_big else R.layout.w_small)
    rv.setImageViewBitmap(R.id.bg, Art.bg(w, h, p.radius * dm.density, p.c1, p.c2, p.angle,
        st.progress, st.live && big, p.text))

    val labelText = st.label + if (st.room.isEmpty()) "" else " • каб. ${st.room}"
    rv.setTextColor(R.id.label, alpha(p.text, 200))
    rv.setTextColor(R.id.title, p.text)
    rv.setTextColor(R.id.sub, alpha(p.text, 225))
    rv.setTextViewText(R.id.label, labelText)
    rv.setTextViewText(R.id.title, st.title)

    if (big) {
        rv.setTextColor(R.id.badge, p.text)
        rv.setTextColor(R.id.next, alpha(p.text, 215))
        rv.setTextColor(R.id.today, alpha(p.text, 170))
        rv.setTextViewText(R.id.sub, (if (st.room.isEmpty()) "" else "каб. ${st.room} • ") + st.sub)
        rv.setTextViewText(R.id.badge, st.badge)
        rv.setTextViewText(R.id.next, st.next)
        rv.setTextViewText(R.id.today, st.today)
    } else {
        rv.setTextViewText(R.id.sub, st.sub + " • " + st.badge)
    }

    val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    rv.setOnClickPendingIntent(R.id.root, pi)
    mgr.updateAppWidget(id, rv)
}

object Updater {
    const val TICK = "com.school.widget.TICK"

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
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
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
            Updater.TICK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
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
        if (i.action == Updater.TICK) Updater.all(c)
    }
}

class MainActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var preview: ImageView
    private val h = Handler(Looper.getMainLooper())
    private val apply = Runnable { Updater.all(this) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, bold: Boolean, color: Int = Color.WHITE): TextView {
        val v = TextView(this)
        v.text = t; v.textSize = size; v.setTextColor(color)
        if (bold) v.typeface = Typeface.DEFAULT_BOLD
        v.setPadding(0, dp(6), 0, dp(2))
        return v
    }

    private fun debounce() { h.removeCallbacks(apply); h.postDelayed(apply, 350) }

    private fun drawPreview() {
        preview.setImageBitmap(Art.bg(720, 260, p.radius * 3f, p.c1, p.c2, p.angle, 62, true, p.text))
    }

    private fun picker(title: String, get: () -> Int, set: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(16), 0, 0)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        val hex = tv(String.format("#%06X", 0xFFFFFF and get()), 13f, false, 0xB3FFFFFF.toInt())
        val sw = View(this)
        sw.setBackgroundColor(get())
        head.addView(tv(title, 15f, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(hex)
        head.addView(sw, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(10) })
        box.addView(head)

        val hsv = FloatArray(3)
        Color.colorToHSV(get(), hsv)
        fun upd() {
            val c = Color.HSVToColor(hsv)
            set(c); sw.setBackgroundColor(c)
            hex.text = String.format("#%06X", 0xFFFFFF and c)
            drawPreview(); debounce()
        }
        val names = listOf("Оттенок", "Насыщенность", "Яркость")
        val maxs = listOf(360, 100, 100)
        for (i in 0..2) {
            box.addView(tv(names[i], 11f, false, 0x99FFFFFF.toInt()))
            val sb = SeekBar(this)
            sb.max = maxs[i]
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

    private fun btn(t: String, act: () -> Unit): Button {
        val b = Button(this)
        b.text = t; b.isAllCaps = false
        b.setOnClickListener { act() }
        return b
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = Prefs(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(18), dp(26), dp(18), dp(30))
        val sc = ScrollView(this)
        sc.addView(root)
        setContentView(sc)

        root.addView(tv("Виджет расписания", 24f, true))
        root.addView(tv("Работает офлайн. Добавь виджет: зажми рабочий стол → Виджеты → Расписание 2×1 или 2×2.",
            13f, false, 0xB3FFFFFF.toInt()))

        preview = ImageView(this)
        preview.scaleType = ImageView.ScaleType.FIT_XY
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(14) })
        drawPreview()

        val hs = HorizontalScrollView(this)
        val row = LinearLayout(this)
        hs.addView(row); root.addView(hs)
        val presets = listOf(
            Triple("Индиго", 0xFF4F46E5.toInt(), 0xFF06B6D4.toInt()),
            Triple("Закат", 0xFFF97316.toInt(), 0xFFDB2777.toInt()),
            Triple("Мята", 0xFF10B981.toInt(), 0xFF0EA5E9.toInt()),
            Triple("Океан", 0xFF0EA5E9.toInt(), 0xFF1E3A8A.toInt()),
            Triple("Аметист", 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt()),
            Triple("Графит", 0xFF334155.toInt(), 0xFF0F172A.toInt())
        )
        for (t in presets) row.addView(btn(t.first) { p.c1 = t.second; p.c2 = t.third; debounce(); recreate() })

        root.addView(picker("Цвет градиента 1", { p.c1 }, { p.c1 = it }))
        root.addView(picker("Цвет градиента 2", { p.c2 }, { p.c2 = it }))

        root.addView(tv("Закругление углов", 15f, true))
        val rb = SeekBar(this); rb.max = 44; rb.progress = p.radius - 6
        rb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { p.radius = v + 6; drawPreview(); debounce() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(rb)

        root.addView(tv("Угол градиента", 15f, true))
        val ab = SeekBar(this); ab.max = 360; ab.progress = p.angle
        ab.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { p.angle = v; drawPreview(); debounce() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(ab)

        val tr = LinearLayout(this)
        tr.addView(btn("Белый текст") { p.text = Color.WHITE; drawPreview(); debounce() })
        tr.addView(btn("Тёмный текст") { p.text = 0xFF0B1220.toInt(); drawPreview(); debounce() })
        root.addView(tr)

        root.addView(btn("Обновить виджеты сейчас") { Updater.all(this) })
        if (Build.VERSION.SDK_INT >= 31) root.addView(btn("Разрешить точные будильники") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            catch (e: Exception) {}
        })
        root.addView(btn("Настройки приложения (автозапуск)") {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            catch (e: Exception) {}
        })
        Updater.schedule(this)
    }

    override fun onResume() { super.onResume(); Updater.all(this) }
}
