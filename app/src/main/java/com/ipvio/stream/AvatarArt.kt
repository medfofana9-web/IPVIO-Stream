package com.ipvio.stream

import kotlin.math.cos
import kotlin.math.sin

/**
 * The faces of the avatar tiles, as simple shapes on a unit square (0..1). Drawn by [FofAvatar] on the TV and as SVG
 * ([svg]) on the phone page, so both look the same. Angles: 0° = right, clockwise (screen coordinates).
 */
object AvatarArt {
    sealed class Shape {
        /** Rounded bar (eyes, glasses). */
        class Bar(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) : Shape()
        class Dot(val cx: Float, val cy: Float, val r: Float) : Shape()
        /** Open curve with round ends (smile, closed eyes). */
        class Curve(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val start: Float, val sweep: Float, val width: Float) : Shape()
        /** Lower half disc (big open grin). */
        class Bowl(val cx: Float, val cy: Float, val r: Float) : Shape()
    }

    const val FACES = 6

    private val eyes = listOf(Shape.Bar(0.325f, 0.33f, 0.085f, 0.18f, 0.0425f), Shape.Bar(0.59f, 0.33f, 0.085f, 0.18f, 0.0425f))

    fun shapes(face: Int): List<Shape> = when (face.mod(FACES)) {
        // 0 classic: tall eyes, wide smile
        0 -> eyes + Shape.Curve(0.5f, 0.52f, 0.19f, 0.15f, 25f, 130f, 0.06f)
        // 1 grin: tall eyes, open half-disc mouth
        1 -> eyes + Shape.Bowl(0.5f, 0.6f, 0.15f)
        // 2 wink: one closed eye, small smile
        2 -> listOf(Shape.Curve(0.37f, 0.45f, 0.06f, 0.05f, 200f, 140f, 0.05f), eyes[1], Shape.Curve(0.5f, 0.55f, 0.15f, 0.12f, 30f, 120f, 0.055f))
        // 3 cool: sunglasses and a side smile
        3 -> listOf(
            Shape.Bar(0.25f, 0.37f, 0.22f, 0.13f, 0.045f), Shape.Bar(0.53f, 0.37f, 0.22f, 0.13f, 0.045f),
            Shape.Bar(0.45f, 0.4f, 0.1f, 0.035f, 0.0175f), Shape.Curve(0.54f, 0.6f, 0.12f, 0.08f, 15f, 115f, 0.055f)
        )
        // 4 happy: smiling closed eyes, wide smile
        4 -> listOf(
            Shape.Curve(0.37f, 0.46f, 0.065f, 0.06f, 200f, 140f, 0.05f), Shape.Curve(0.63f, 0.46f, 0.065f, 0.06f, 200f, 140f, 0.05f),
            Shape.Curve(0.5f, 0.53f, 0.18f, 0.14f, 25f, 130f, 0.06f)
        )
        // 5 surprised: round eyes, small round mouth
        else -> listOf(Shape.Dot(0.37f, 0.42f, 0.055f), Shape.Dot(0.63f, 0.42f, 0.055f), Shape.Dot(0.5f, 0.65f, 0.065f))
    }

    private fun hex(c: Long) = "#" + "%06X".format(c and 0xFFFFFF)
    private fun f(v: Float) = "%.2f".format(java.util.Locale.US, v * 100)

    /** The tile as SVG (100×100), same look as on the TV. */
    fun svg(color: Long, ink: Long, face: Int): String {
        val i = hex(ink)
        val sb = StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'>")
        sb.append("<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop offset='0' stop-color='#fff' stop-opacity='.16'/>")
        sb.append("<stop offset='.5' stop-color='#fff' stop-opacity='0'/><stop offset='1' stop-color='#000' stop-opacity='.14'/></linearGradient></defs>")
        sb.append("<rect width='100' height='100' fill='").append(hex(color)).append("'/><rect width='100' height='100' fill='url(#g)'/>")
        for (s in shapes(face)) when (s) {
            is Shape.Bar -> sb.append("<rect x='${f(s.x)}' y='${f(s.y)}' width='${f(s.w)}' height='${f(s.h)}' rx='${f(s.r)}' fill='$i'/>")
            is Shape.Dot -> sb.append("<circle cx='${f(s.cx)}' cy='${f(s.cy)}' r='${f(s.r)}' fill='$i'/>")
            is Shape.Curve -> {
                val a0 = Math.toRadians(s.start.toDouble()); val a1 = Math.toRadians((s.start + s.sweep).toDouble())
                val x0 = s.cx + s.rx * cos(a0).toFloat(); val y0 = s.cy + s.ry * sin(a0).toFloat()
                val x1 = s.cx + s.rx * cos(a1).toFloat(); val y1 = s.cy + s.ry * sin(a1).toFloat()
                val large = if (s.sweep > 180f) 1 else 0
                sb.append("<path d='M${f(x0)} ${f(y0)} A${f(s.rx)} ${f(s.ry)} 0 $large 1 ${f(x1)} ${f(y1)}' fill='none' stroke='$i' stroke-width='${f(s.width)}' stroke-linecap='round'/>")
            }
            is Shape.Bowl -> sb.append("<path d='M${f(s.cx - s.r)} ${f(s.cy)} A${f(s.r)} ${f(s.r)} 0 0 0 ${f(s.cx + s.r)} ${f(s.cy)} Z' fill='$i'/>")
        }
        return sb.append("</svg>").toString()
    }
}
