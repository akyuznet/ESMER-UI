package com.example.esmerui.cluster

import android.graphics.Matrix
import android.graphics.Rect

/** How the cluster's 1920x720 image fills a preview that is not that shape. */
enum class MirrorScaling(val label: String, val description: String) {
    FIT("Fit", "Whole cluster, letterboxed"),
    STRETCH("Stretch", "Fills the preview, shapes may distort"),
    ZOOM("Zoom", "Fills the preview, edges cropped"),
    ;

    companion object {
        fun from(name: String?): MirrorScaling = entries.firstOrNull { it.name == name } ?: FIT
    }
}

/** Where on the cluster the cast app's window sits. */
enum class ClusterPlacement(val label: String) {
    FULL("Full"),
    LEFT_HALF("Left half"),
    RIGHT_HALF("Right half"),
    CENTER("Center"),
    /** Whatever the resize frame was dragged to; stored separately. */
    CUSTOM("Custom"),
    ;

    companion object {
        fun from(name: String?): ClusterPlacement = entries.firstOrNull { it.name == name } ?: FULL
    }
}

/**
 * The arithmetic both ends of the mirror share: the daemon projects the cluster
 * with these rectangles, the preview maps a finger back through the same ones,
 * and the resize frame draws the cast window's bounds over them. Kept in one
 * place so a scaling mode cannot drift between the picture and the touches.
 */
object ClusterGeometry {

    const val WIDTH = ClusterProjection.CLUSTER_WIDTH
    const val HEIGHT = ClusterProjection.CLUSTER_HEIGHT

    /** The cluster, whole. */
    val full: Rect get() = Rect(0, 0, WIDTH, HEIGHT)

    /** The window bounds a placement stands for; [ClusterPlacement.CUSTOM] takes [custom]. */
    fun bounds(placement: ClusterPlacement, custom: Rect?): Rect = when (placement) {
        ClusterPlacement.FULL -> full
        ClusterPlacement.LEFT_HALF -> Rect(0, 0, WIDTH / 2, HEIGHT)
        ClusterPlacement.RIGHT_HALF -> Rect(WIDTH / 2, 0, WIDTH, HEIGHT)
        ClusterPlacement.CENTER -> Rect(WIDTH / 4, 0, WIDTH * 3 / 4, HEIGHT)
        ClusterPlacement.CUSTOM -> custom?.takeIf { !it.isEmpty } ?: full
    }

    /**
     * The source rectangle on the cluster and the destination rectangle in a
     * view of the given size, for a scaling mode.
     */
    fun mirrorRects(scaling: MirrorScaling, viewWidth: Int, viewHeight: Int): Pair<Rect, Rect> {
        val vw = viewWidth.coerceAtLeast(1)
        val vh = viewHeight.coerceAtLeast(1)
        return when (scaling) {
            MirrorScaling.FIT -> {
                val scale = minOf(vw.toFloat() / WIDTH, vh.toFloat() / HEIGHT)
                val dw = (WIDTH * scale).toInt()
                val dh = (HEIGHT * scale).toInt()
                val ox = (vw - dw) / 2
                val oy = (vh - dh) / 2
                full to Rect(ox, oy, ox + dw, oy + dh)
            }
            MirrorScaling.STRETCH -> full to Rect(0, 0, vw, vh)
            MirrorScaling.ZOOM -> {
                val scale = maxOf(vw.toFloat() / WIDTH, vh.toFloat() / HEIGHT)
                val sw = (vw / scale).toInt()
                val sh = (vh / scale).toInt()
                val sx = (WIDTH - sw) / 2
                val sy = (HEIGHT - sh) / 2
                Rect(sx, sy, sx + sw, sy + sh) to Rect(0, 0, vw, vh)
            }
        }
    }

    /** A transform taking view coordinates onto the cluster for the given rectangles. */
    fun viewToCluster(source: Rect, target: Rect): Matrix = Matrix().apply {
        postTranslate(-target.left.toFloat(), -target.top.toFloat())
        postScale(
            source.width().toFloat() / target.width().coerceAtLeast(1),
            source.height().toFloat() / target.height().coerceAtLeast(1),
        )
        postTranslate(source.left.toFloat(), source.top.toFloat())
    }

    /** Serialises bounds as `l,t,r,b`, the form the settings file holds. */
    fun encode(rect: Rect): String = "${rect.left},${rect.top},${rect.right},${rect.bottom}"

    fun decode(text: String?): Rect? {
        val parts = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
        if (parts.size != 4 || parts.any { it == null }) return null
        val rect = Rect(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
        return rect.takeIf { it.width() > 0 && it.height() > 0 }
    }
}
