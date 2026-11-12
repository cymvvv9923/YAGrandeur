package ai.gilwi.anbu

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.cos

/**
 * 메인 「메시지 지도」의 전국 보기: 시·도 경계를 유리판처럼 그린다 (두께·바닥 그림자·메시지가 있는 지역의 빛).
 * 지역 알약(사진·이름·못 들은 수)은 자식 뷰로 받아, 그 지역 위에 서로 겹치지 않게 놓는다.
 */
class GlassMap(context: Context) : FrameLayout(context) {

    companion object {
        /** 도시 (위도, 경도, 규모 0~1) — 야간 불빛용 */
        private val CITIES = listOf(
            Triple(37.56, 126.98, 1.0f), Triple(37.46, 126.70, 0.6f), Triple(37.27, 127.01, 0.55f), Triple(37.42, 127.13, 0.45f),
            Triple(37.66, 126.83, 0.45f), Triple(37.50, 126.78, 0.4f), Triple(37.24, 127.18, 0.4f), Triple(37.32, 126.83, 0.35f),
            Triple(37.20, 126.83, 0.3f), Triple(36.99, 127.11, 0.3f), Triple(37.74, 127.05, 0.3f), Triple(35.18, 129.08, 0.85f),
            Triple(35.87, 128.60, 0.7f), Triple(35.16, 126.85, 0.55f), Triple(36.35, 127.38, 0.6f), Triple(35.54, 129.31, 0.5f),
            Triple(36.48, 127.29, 0.3f), Triple(36.64, 127.49, 0.4f), Triple(36.81, 127.15, 0.4f), Triple(35.82, 127.15, 0.4f),
            Triple(35.23, 128.68, 0.5f), Triple(35.23, 128.89, 0.35f), Triple(36.02, 129.34, 0.4f), Triple(36.12, 128.34, 0.35f),
            Triple(35.86, 129.22, 0.25f), Triple(36.57, 128.73, 0.2f), Triple(35.18, 128.11, 0.3f), Triple(34.76, 127.66, 0.3f),
            Triple(34.95, 127.49, 0.3f), Triple(34.81, 126.39, 0.3f), Triple(35.97, 126.74, 0.25f), Triple(35.95, 126.96, 0.25f),
            Triple(37.34, 127.92, 0.3f), Triple(37.88, 127.73, 0.3f), Triple(37.75, 128.88, 0.3f), Triple(38.21, 128.59, 0.15f),
            Triple(37.52, 129.11, 0.15f), Triple(36.99, 127.93, 0.2f), Triple(33.50, 126.53, 0.35f), Triple(33.25, 126.56, 0.2f),
            Triple(34.85, 128.43, 0.15f), Triple(34.88, 128.62, 0.2f), Triple(36.79, 127.00, 0.25f), Triple(36.78, 126.45, 0.2f),
            Triple(37.00, 127.27, 0.2f), Triple(37.64, 127.21, 0.3f), Triple(37.38, 126.93, 0.35f), Triple(37.15, 128.21, 0.12f),
            Triple(36.44, 129.06, 0.12f), Triple(35.43, 127.39, 0.12f), Triple(36.33, 126.61, 0.12f), Triple(35.06, 126.52, 0.12f),
        )
    }

    /**
     * 메시지가 있는 권역 하나: 권역에 든 시·도 코드, 점·알약을 놓을 자리, 윗면 색 두 가지(위·아래), 빛 색, 알약,
     * level(0~1): 못 들은 메시지가 많을수록 1 — 밤에는 불빛이 많아지고 낮에는 색이 진해진다
     */
    class Mark(val key: String, val codes: Set<String>, val lat: Double, val lon: Double, val top: Int, val bottom: Int, val glow: Int, val pill: View, val level: Float = 1f)

    var marks: List<Mark> = emptyList()
        set(v) {
            field = v
            removeAllViews()
            v.forEach { addView(it.pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
            requestLayout(); markDirty()
        }
    /** 고른 지역 코드 (없으면 전체) */
    var selected: String? = null
        set(v) { field = v; markDirty() }
    /** 지금 위치 (위도, 경도) */
    var here: Pair<Double, Double>? = null
        set(v) { field = v; invalidate() }
    var onMarkTap: ((Mark) -> Unit)? = null
    /**
     * 메시지가 남겨진 자리 (위도, 경도, 못 들은 것인지). 밤 불빛은 이 자리에서만 난다.
     * 가까운 자리끼리 묶어, 메시지가 많이 모인 곳일수록 크고 밝다. 들은 메시지는 은은하게.
     */
    var lights: List<Triple<Double, Double, Boolean>> = emptyList()
        set(v) { field = v; markDirty() }
    /** 야간: 남색 유리 + 도시 불빛 */
    var night = false
        set(v) { field = v; markDirty() }

    private val k = cos(Math.toRadians(36.0))
    private var scale = 1f; private var offX = 0f; private var offY = 0f
    private var minX = 0.0; private var minY = 0.0
    private val paths = HashMap<String, Path>()
    private val all = Path()
    private val hit = HashMap<String, android.graphics.Region>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeJoin = Paint.Join.ROUND }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 110, 132, 170) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        // 흐림(BlurMaskFilter)·SCREEN 섞기는 소프트웨어 그리기에서 확실히 나온다.
        // 무거운 바탕은 비트맵에 한 번만 그려 두므로 매 프레임은 가볍다 (에뮬레이터에서 하드웨어보다 빨랐다)
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    private fun px(lon: Double) = ((lon * k - minX) * scale + offX).toFloat()
    private fun py(lat: Double) = ((-lat - minY) * scale + offY).toFloat()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        build(w, h)
    }

    private fun build(w: Int, h: Int) {
        val provs = Regions.provinces
        if (provs.isEmpty() || w == 0 || h == 0) return
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        provs.forEach { r -> r.rings.forEach { ring -> var i = 0; while (i < ring.size) { val x = ring[i + 1] * k; val y = -ring[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; i += 2 } } }
        minX = x0; minY = y0
        val pad = 0.025f
        val s = minOf(w * (1 - 2 * pad) / (x1 - x0), h * (1 - 2 * pad) / (y1 - y0))
        scale = s.toFloat()
        offX = ((w - (x1 - x0) * s) / 2).toFloat(); offY = ((h - (y1 - y0) * s) / 2).toFloat()
        paths.clear(); all.reset(); hit.clear()
        val clip = android.graphics.Region(0, 0, w, h)
        provs.forEach { r ->
            val p = Path()
            r.rings.forEach { ring ->
                var i = 0
                while (i < ring.size) { val x = px(ring[i + 1]); val y = py(ring[i]); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y); i += 2 }
                p.close()
            }
            paths[r.code] = p; all.addPath(p)
            hit[r.code] = android.graphics.Region().apply { setPath(p, clip) }
        }
        val u = scale / 118f // 지도 크기에 맞춘 단위
        stroke.strokeWidth = (1.8f * u).coerceAtLeast(1.4f)
        shadow.maskFilter = BlurMaskFilter((14 * u).coerceAtLeast(6f), BlurMaskFilter.Blur.NORMAL)
        glow.maskFilter = BlurMaskFilter((22 * u).coerceAtLeast(10f), BlurMaskFilter.Blur.NORMAL)
        dirty = true
        invalidate(); requestLayout()
    }

    // 그리기 ------------------------------------------------------------
    // 움직이지 않는 부분(그림자·두께·흐린 빛·땅·바다 격자)은 한 장으로 미리 그려 두고(base),
    // 매 프레임에는 움직이는 것(반짝이는 불빛·숨 쉬는 메시지 불빛·위성·물결)과 점만 그 위에 그린다.
    // 흐림(BlurMaskFilter)은 소프트웨어로 그려 무겁기 때문에, 매 프레임 다시 그리면 안 된다.

    private var base: android.graphics.Bitmap? = null
    private var dirty = true
    private fun markDirty() { dirty = true; invalidate() }

    /** 반짝일 작은 불빛 (x, y, 반지름, 색, 빛의 위상, 빠르기) */
    private class Spark(val x: Float, val y: Float, val r: Float, val color: Int, val phase: Float, val speed: Float)
    private var sparks: List<Spark> = emptyList()
    /** 숨 쉴 메시지 불빛 덩어리 (x, y, 무게, 못 들은 수) */
    private var clusters: List<FloatArray> = emptyList()
    private val startAt = android.os.SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paths.isEmpty()) build(width, height)
        if (paths.isEmpty() || width == 0 || height == 0) return
        val u = scale / 118f
        if (dirty || base?.width != width || base?.height != height) {
            base?.recycle()
            base = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888).also { drawBase(Canvas(it), u) }
            prepareLive(u)
            dirty = false
        }
        base?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val t = (android.os.SystemClock.uptimeMillis() - startAt) / 1000f
        val moving = animating()
        if (night) drawNightLive(canvas, u, if (moving) t else 0f) else drawDayLive(canvas, u, if (moving) t else 0f)
        // 지역 점
        marks.forEach { m ->
            val x = px(m.lon); val y = py(m.lat)
            dot.style = Paint.Style.FILL; dot.color = if (night) Color.rgb(16, 24, 60) else Color.WHITE; canvas.drawCircle(x, y, 6 * u.coerceAtLeast(0.8f), dot)
            dot.style = Paint.Style.STROKE; dot.strokeWidth = 2.5f; dot.color = if (night) Color.rgb(157, 188, 255) else Color.rgb(45, 95, 214); canvas.drawCircle(x, y, 6 * u.coerceAtLeast(0.8f), dot)
        }
        // 지금 위치: 바깥 고리가 천천히 퍼졌다 사라진다
        here?.let { (lat, lon) ->
            val x = px(lon); val y = py(lat)
            dot.style = Paint.Style.FILL
            if (moving) {
                val p = (t % 2.4f) / 2.4f
                dot.color = Color.argb((90 * (1 - p)).toInt(), 45, 95, 214); canvas.drawCircle(x, y, 9f + 18f * p, dot)
            } else { dot.color = Color.argb(60, 45, 95, 214); canvas.drawCircle(x, y, 16f, dot) }
            dot.color = Color.WHITE; canvas.drawCircle(x, y, 9f, dot)
            dot.color = Color.rgb(45, 95, 214); canvas.drawCircle(x, y, 6f, dot)
        }
        // 다음 프레임 (약 15fps: 움직임이 느려 이걸로 충분하고, 화면 누름이 굼뜨지 않게 여유를 둔다).
        // 보이지 않거나 실제 지도로 확대되어 흐려졌으면 멈춘다
        // 다른 일로 다시 그려져도 예약은 늘 하나만 (예약이 겹쳐 쌓이면 프레임이 불어난다)
        if (moving) { removeCallbacks(tick); postDelayed(tick, 66) }
    }

    private val tick = Runnable { invalidate() }

    /** 움직여도 되는지: 화면에 보이고, 실제 지도로 확대되지 않았고, 기기의 애니메이션이 켜져 있을 때 */
    private fun animating() = isAttachedToWindow && isShown && alpha > 0.4f && android.animation.ValueAnimator.areAnimatorsEnabled()

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun setAlpha(alpha: Float) {
        val wasStill = this.alpha <= 0.4f
        super.setAlpha(alpha)
        if (wasStill && alpha > 0.4f) invalidate() // 전국 보기로 돌아오면 다시 움직인다
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        base?.recycle(); base = null; dirty = true
    }

    /** 움직이지 않는 바탕 한 장 */
    private fun drawBase(canvas: Canvas, u: Float) {
        if (!night) drawDaySeaStill(canvas, u)
        // 바닥 그림자
        shadow.color = if (night) Color.argb(150, 2, 4, 20) else Color.argb(70, 110, 132, 170)
        canvas.save(); canvas.translate(6 * u, 20 * u); canvas.drawPath(all, shadow); canvas.restore()
        // 두께 (두 겹)
        fill.shader = null
        fill.color = if (night) Color.rgb(9, 14, 42) else Color.rgb(176, 190, 214); canvas.save(); canvas.translate(0f, 10 * u); canvas.drawPath(all, fill); canvas.restore()
        fill.color = if (night) Color.rgb(20, 29, 70) else Color.rgb(201, 212, 230); canvas.save(); canvas.translate(0f, 5 * u); canvas.drawPath(all, fill); canvas.restore()
        // 빛: 고른 지역은 진하게, 나머지는 옅게
        marks.forEach { m ->
            val lv = m.level.coerceIn(0f, 1f)
            glow.color = if (night) m.glow else strength(m.glow, lv)
            glow.alpha = when { selected == null -> (60 + 180 * lv).toInt(); selected == m.key -> 255; else -> 40 }
            m.codes.forEach { c -> paths[c]?.let { canvas.drawPath(it, glow) } }
        }
        // 윗면
        val bounds = RectF(); all.computeBounds(bounds, true)
        val land = if (night) LinearGradient(0f, bounds.top, bounds.width() * 0.3f, bounds.bottom, Color.rgb(36, 50, 104), Color.rgb(22, 31, 74), Shader.TileMode.CLAMP)
            else LinearGradient(0f, bounds.top, bounds.width() * 0.3f, bounds.bottom, Color.WHITE, Color.rgb(231, 237, 247), Shader.TileMode.CLAMP)
        val dimTo = if (night) Color.rgb(24, 33, 78) else Color.WHITE
        val markCodes = marks.flatMap { it.codes }.toSet()
        paths.forEach { (code, p) -> if (code !in markCodes) { fill.shader = land; canvas.drawPath(p, fill) } }
        marks.forEach { m ->
            val b = regionRect(m.codes) ?: return@forEach
            val dim = selected != null && selected != m.key
            // 낮: 권역마다 제 색을 쓰되, 메시지가 적으면 거의 흰빛으로 옅게, 많으면 진하게. 밤은 색 대신 불빛으로
            val lv = m.level.coerceIn(0f, 1f)
            val top = if (night) m.top else strength(m.top, lv)
            val bottom = if (night) m.bottom else strength(m.bottom, lv)
            fill.shader = LinearGradient(0f, b.top, 0f, b.bottom, if (dim) blend(top, dimTo, 0.6f) else top, if (dim) blend(bottom, dimTo, 0.6f) else bottom, Shader.TileMode.CLAMP)
            m.codes.forEach { c -> paths[c]?.let { canvas.drawPath(it, fill) } }
        }
        fill.shader = null
        if (night) drawNightStill(canvas, u)
        stroke.color = if (night) Color.argb(110, 150, 175, 255) else Color.WHITE
        canvas.drawPath(all, stroke)
    }

    /** 그 지역이 이 뷰 안에서 차지하는 네모 (확대 전환용) */
    fun regionRect(codes: Set<String>): RectF? {
        var out: RectF? = null
        Regions.provinces.filter { it.code in codes }.forEach { r ->
            val ring = Regions.mainRing(r) ?: return@forEach
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = -Float.MAX_VALUE; var bb = -Float.MAX_VALUE
            var i = 0
            while (i < ring.size) { val x = px(ring[i + 1]); val y = py(ring[i]); if (x < l) l = x; if (x > rr) rr = x; if (y < t) t = y; if (y > bb) bb = y; i += 2 }
            val b = RectF(l, t, rr, bb)
            out = out?.also { it.union(b) } ?: b
        }
        return out
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        for (i in 0 until childCount) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    /** 알약을 지역 점 위·아래·옆 중 겹치지 않는 자리에 둔다 */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (paths.isEmpty()) build(width, height)
        // 지역 점은 가리지 않는다
        val placed = marks.map { m -> val x = px(m.lon); val y = py(m.lat); RectF(x - 10, y - 10, x + 10, y + 10) }.toMutableList()
        marks.forEach { m ->
            val v = m.pill
            val w = v.measuredWidth.toFloat(); val h = v.measuredHeight.toFloat()
            val x = px(m.lon); val y = py(m.lat)
            // 가까운 자리부터: 위 · 오른쪽 · 왼쪽 · 아래 · 대각선, 거리 3단계
            val tries = listOf(14f, 56f, 104f).flatMap { gap ->
                listOf(
                    RectF(x - w / 2, y - gap - h, x + w / 2, y - gap),
                    RectF(x + gap, y - h / 2, x + gap + w, y + h / 2),
                    RectF(x - gap - w, y - h / 2, x - gap, y + h / 2),
                    RectF(x - w / 2, y + gap, x + w / 2, y + gap + h),
                    RectF(x + gap, y - gap - h, x + gap + w, y - gap),
                    RectF(x - gap - w, y - gap - h, x - gap, y - gap),
                    RectF(x + gap, y + gap, x + gap + w, y + gap + h),
                    RectF(x - gap - w, y + gap, x - gap, y + gap + h),
                )
            }.map { fit(it) }
            val best = tries.firstOrNull { c -> placed.none { RectF.intersects(it, c) } } ?: tries.first()
            placed += RectF(best.left - 6, best.top - 6, best.right + 6, best.bottom + 6)
            v.layout(best.left.toInt(), best.top.toInt(), best.right.toInt(), best.bottom.toInt())
        }
    }

    private fun fit(r: RectF): RectF {
        val dx = when { r.left < 4 -> 4 - r.left; r.right > width - 4 -> width - 4 - r.right; else -> 0f }
        val dy = when { r.top < 4 -> 4 - r.top; r.bottom > height - 4 -> height - 4 - r.bottom; else -> 0f }
        return RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (alpha < 0.4f || visibility != VISIBLE) return false // 확대되어 흐려진 뒤에는 아래 실제 지도가 터치를 받는다
        if (e.action == MotionEvent.ACTION_UP) {
            val code = hit.entries.firstOrNull { it.value.contains(e.x.toInt(), e.y.toInt()) }?.key
            marks.firstOrNull { code != null && code in it.codes }?.let { onMarkTap?.invoke(it) }
        }
        return true
    }

    /** 한국의 야경: 바탕(권역의 따뜻한 빛 · 도시의 옅은 빛)은 한 장에, 반짝임과 메시지 불빛은 매 프레임 */
    private val lightGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN) }
    private val lightDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN) }
    /** 도시가 든 시·도 코드 (한 번만 찾는다) */
    private val cityProv by lazy { CITIES.map { (lat, lon, _) -> Regions.provinceOf(lat, lon)?.code } }
    private val regionFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN) }

    private fun cityLevel(idx: Int) = cityProv.getOrNull(idx)?.let { code -> marks.firstOrNull { code in it.codes }?.level }

    private fun drawNightStill(canvas: Canvas, u: Float) {
        canvas.save(); canvas.clipPath(all)
        // 메시지가 있는 권역은 땅 전체가 은은하게 따뜻해진다 (많을수록 조금 더)
        marks.forEach { m ->
            val lv = m.level.coerceIn(0f, 1f)
            regionFill.color = Color.argb((22 + 38 * lv).toInt(), 255, 196, 120)
            m.codes.forEach { c -> paths[c]?.let { canvas.drawPath(it, regionFill) } }
        }
        // 도시 바탕빛: 메시지가 없는 권역은 아주 옅게, 메시지가 있는 권역은 그보다 밝게
        CITIES.forEachIndexed { idx, (lat, lon, w0) ->
            val lv = cityLevel(idx)
            val boost = if (lv == null) 1f else 2.2f + 1.8f * lv.coerceIn(0f, 1f)
            val x = px(lon); val y = py(lat)
            val r = (7f + 30f * w0) * u
            lightGlow.shader = android.graphics.RadialGradient(x, y, r, intArrayOf(Color.argb((60 * w0 * boost).toInt().coerceAtMost(220), 255, 205, 125), Color.argb(0, 255, 160, 80)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, lightGlow)
        }
        lightGlow.shader = null
        canvas.restore()
    }

    /** 매 프레임 그릴 것들의 자리를 미리 정한다 (바탕을 새로 그릴 때만) */
    private fun prepareLive(u: Float) {
        val rnd = java.util.Random(42)
        val list = mutableListOf<Spark>()
        // 메시지가 있는 권역의 도시엔 작은 불빛 (반짝인다)
        CITIES.forEachIndexed { idx, (lat, lon, w0) ->
            val lv = cityLevel(idx) ?: return@forEachIndexed
            val x = px(lon); val y = py(lat); val r = (7f + 30f * w0) * u
            repeat((4 + 14 * w0 * (0.5f + lv)).toInt()) {
                val a = rnd.nextDouble() * Math.PI * 2; val d = Math.sqrt(rnd.nextDouble()) * r * 1.1
                list += Spark(x + (Math.cos(a) * d).toFloat(), y + (Math.sin(a) * d).toFloat(), (0.4f + rnd.nextFloat() * 0.7f) * u.coerceAtLeast(0.9f),
                    Color.argb(90 + rnd.nextInt(80), 255, 226 + rnd.nextInt(25), 160 + rnd.nextInt(60)), rnd.nextFloat() * 6.28f, 0.6f + rnd.nextFloat() * 1.6f)
            }
        }
        // 메시지 자리의 불빛 덩어리: 화면에서 가까운(약 14km) 자리끼리. 못 들은 메시지 1개 = 1, 들은 메시지 = 0.35
        val cl = mutableListOf<FloatArray>()
        lights.forEach { (lat, lon, fresh) ->
            val x = px(lon); val y = py(lat)
            val c = cl.firstOrNull { Math.hypot((it[0] - x).toDouble(), (it[1] - y).toDouble()) < 14 * u }
            val wt = if (fresh) 1f else 0.35f
            if (c == null) cl += floatArrayOf(x, y, wt, if (fresh) 1f else 0f) else { c[2] += wt; if (fresh) c[3] += 1f }
        }
        cl.forEach { (x, y, wt, fresh) ->
            val n = wt.coerceAtMost(5f); val r = (12f + 9f * n) * u; val bright = if (fresh > 0) 1f else 0.5f
            repeat((8 + 10 * n).toInt()) {
                val a = rnd.nextDouble() * Math.PI * 2; val d = Math.sqrt(rnd.nextDouble()) * r * 1.25
                list += Spark(x + (Math.cos(a) * d).toFloat(), y + (Math.sin(a) * d).toFloat(), (0.5f + rnd.nextFloat() * 0.8f) * u.coerceAtLeast(0.9f),
                    Color.argb(((150 + rnd.nextInt(90)) * bright).toInt(), 255, 230 + rnd.nextInt(20), 170 + rnd.nextInt(50)), rnd.nextFloat() * 6.28f, 0.8f + rnd.nextFloat() * 1.8f)
            }
        }
        sparks = list; clusters = cl
    }

    /** 밤의 움직임: 불빛이 별처럼 반짝이고, 메시지 불빛은 숨 쉬듯 커졌다 작아지고, 위성 불빛이 깜빡이며 지나간다 */
    private fun drawNightLive(canvas: Canvas, u: Float, t: Float) {
        canvas.save(); canvas.clipPath(all)
        clusters.forEachIndexed { i, c ->
            val x = c[0]; val y = c[1]; val n = c[2].coerceAtMost(5f)
            val breathe = 1f + 0.10f * Math.sin(t * 1.3 + i * 1.7).toFloat()
            val r = (12f + 9f * n) * u * breathe
            val bright = if (c[3] > 0) 1f else 0.5f
            lightGlow.shader = android.graphics.RadialGradient(x, y, r, intArrayOf(Color.argb((235 * bright).toInt(), 255, 218, 148), Color.argb((105 * bright).toInt(), 255, 178, 98), Color.argb(0, 255, 160, 80)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, r, lightGlow)
            lightDot.color = Color.argb((250 * bright).toInt(), 255, 248, 225)
            canvas.drawCircle(x, y, (1.8f + 0.5f * n) * u.coerceAtLeast(0.9f), lightDot)
        }
        lightGlow.shader = null
        sparks.forEach { s ->
            val tw = 0.55f + 0.45f * Math.sin((t * s.speed + s.phase).toDouble()).toFloat()
            lightDot.color = Color.argb((Color.alpha(s.color) * tw).toInt(), Color.red(s.color), Color.green(s.color), Color.blue(s.color))
            canvas.drawCircle(s.x, s.y, s.r, lightDot)
        }
        canvas.restore()
        // 위성: 궤도를 따라 아주 천천히(한 바퀴 70초) 지나가는 깜빡이는 점
        val (sx, sy) = orbitPoint(t)
        val blink = if ((t % 1.6f) < 0.18f) 1f else 0.35f
        lightDot.color = Color.argb((230 * blink).toInt(), 200, 220, 255)
        canvas.drawCircle(sx, sy, 2.2f * u.coerceAtLeast(0.9f), lightDot)
        lightDot.color = Color.argb((60 * blink).toInt(), 160, 190, 255)
        canvas.drawCircle(sx, sy, 7f * u.coerceAtLeast(0.9f), lightDot)
    }

    /** 위성 궤도: 지도를 비스듬히 감싸는 타원 위의 한 점 */
    private fun orbitPoint(t: Float): Pair<Float, Float> {
        val cx = width * 0.52f; val cy = height * 0.5f; val rx = width * 0.47f; val ry = height * 0.17f
        val a = Math.toRadians(205.0 + t * 360.0 / 70.0)
        val ex = (rx * Math.cos(a)).toFloat(); val ey = (ry * Math.sin(a)).toFloat()
        val rot = Math.toRadians(-24.0)
        return (cx + ex * Math.cos(rot) - ey * Math.sin(rot)).toFloat() to (cy + ex * Math.sin(rot) + ey * Math.cos(rot)).toFloat()
    }

    private val seaLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val seaText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Color.rgb(96, 128, 186)

    /** 낮 지도의 바다 장식 중 움직이지 않는 것: 위경도 격자 · 해안의 옅은 띠 · 바다 이름 · 궤도 점선 (모두 아주 옅게) */
    private fun drawDaySeaStill(canvas: Canvas, u: Float) {
        seaLine.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f * u, 6f * u), 0f)
        seaLine.strokeWidth = 1f; seaLine.color = Color.argb(34, Color.red(ink), Color.green(ink), Color.blue(ink))
        seaText.textSize = 10.5f * u.coerceAtLeast(0.9f); seaText.color = Color.argb(70, Color.red(ink), Color.green(ink), Color.blue(ink))
        for (lat in 33..38) { val y = py(lat.toDouble()); canvas.drawLine(0f, y, width.toFloat(), y, seaLine); canvas.drawText("${lat}°N", width - 22f * u, y - 3f * u, seaText) }
        for (lon in 125..130) { val x = px(lon.toDouble()); canvas.drawLine(x, 0f, x, height.toFloat(), seaLine); canvas.drawText("${lon}°E", x, height - 6f * u, seaText) }
        seaLine.pathEffect = null
        // 해안의 옅은 띠 (바다 깊이처럼). 그 위로 물결이 움직이며 퍼진다
        listOf(28f to 12, 14f to 20).forEach { (w, a) ->
            seaLine.strokeWidth = w * u; seaLine.strokeJoin = Paint.Join.ROUND
            seaLine.color = Color.argb(a, 120, 168, 230)
            canvas.drawPath(all, seaLine)
        }
        seaText.textSize = 15f * u.coerceAtLeast(0.9f); seaText.letterSpacing = 0.6f
        seaText.color = Color.argb(80, Color.red(ink), Color.green(ink), Color.blue(ink))
        canvas.drawText("서 해", px(125.0), py(36.3), seaText)
        canvas.drawText("동 해", px(130.2), py(37.55), seaText)
        canvas.drawText("남 해", px(127.9), py(33.85), seaText)
        seaText.letterSpacing = 0f
        val cx = width * 0.52f; val cy = height * 0.5f; val rx = width * 0.47f; val ry = height * 0.17f
        canvas.save(); canvas.rotate(-24f, cx, cy)
        seaLine.pathEffect = android.graphics.DashPathEffect(floatArrayOf(2f * u, 7f * u), 0f)
        seaLine.strokeWidth = 1.4f * u.coerceAtLeast(0.9f); seaLine.color = Color.argb(70, Color.red(ink), Color.green(ink), Color.blue(ink))
        canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), seaLine)
        seaLine.pathEffect = null
        canvas.restore()
    }

    /** 낮의 움직임: 해안에서 물결이 바깥으로 천천히 퍼지고, 위성이 궤도를 따라 돈다 */
    private fun drawDayLive(canvas: Canvas, u: Float, t: Float) {
        // 물결 두 줄이 해안에서 바다 쪽으로 퍼지며 옅어진다 (한 번에 4초). 땅 위로는 그리지 않는다
        canvas.save()
        canvas.clipOutPath(all)
        seaLine.strokeJoin = Paint.Join.ROUND
        repeat(2) { k ->
            val p = ((t / 4f) + k * 0.5f) % 1f
            seaLine.strokeWidth = (6f + 70f * p) * u
            seaLine.color = Color.argb((46 * (1 - p) * (1 - p)).toInt(), 120, 168, 230)
            canvas.drawPath(all, seaLine)
        }
        canvas.restore()
        // 위성: 궤도를 따라 한 바퀴 70초, 몸을 살짝 기울였다 폈다 한다
        val (sx, sy) = orbitPoint(t)
        canvas.save(); canvas.translate(sx, sy); canvas.rotate(-24f + 8f * Math.sin(t * 0.5).toFloat())
        val sz = 8f * u.coerceAtLeast(0.9f)
        satPaint.color = Color.argb(130, Color.red(ink), Color.green(ink), Color.blue(ink))
        canvas.drawRoundRect(RectF(-sz * 0.8f, -sz * 0.8f, sz * 0.8f, sz * 0.8f), sz * 0.3f, sz * 0.3f, satPaint) // 몸체
        satPaint.color = Color.argb(110, 90, 140, 220)
        canvas.drawRect(RectF(-sz * 3.2f, -sz * 0.55f, -sz * 1.2f, sz * 0.55f), satPaint) // 왼쪽 날개
        canvas.drawRect(RectF(sz * 1.2f, -sz * 0.55f, sz * 3.2f, sz * 0.55f), satPaint)   // 오른쪽 날개
        satPaint.color = Color.argb(100, Color.red(ink), Color.green(ink), Color.blue(ink))
        canvas.drawRect(RectF(-sz * 1.2f, -sz * 0.12f, sz * 1.2f, sz * 0.12f), satPaint) // 날개 축
        canvas.restore()
    }

    /**
     * 권역 색의 진하기: lv 0이면 채도를 거의 빼 흰빛에 가깝게, 1이면 채도를 끌어올리고 조금 어둡게.
     * 색상(빨강·초록…)은 그대로 두어 권역마다 제 색을 지킨다.
     */
    private fun strength(c: Int, lv: Float): Int {
        // 채도·밝기는 원래 색과 상관없이 개수로만 정한다: 같은 개수면 어느 권역이든 같은 진하기
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = 0.06f + 0.40f * lv
        hsv[2] = 0.995f - 0.13f * lv
        return Color.HSVToColor(Color.alpha(c), hsv)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
}
