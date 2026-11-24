package com.draftassist

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0; private var capH = 0
    private var overlay: Overlay? = null
    private lateinit var engine: DraftEngine
    private lateinit var detector: Detector
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("draft-capture").also { it.start() }
    private val bg = Handler(thread.looper)
    @Volatile private var running = false
    @Volatile private var manualLane: String = "gold"
    @Volatile private var lastRes: Detector.Result? = null
    private var frames = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("draft", "Draft assistant", NotificationManager.IMPORTANCE_LOW))
            val n = NotificationCompat.Builder(this, "draft").setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("MLBB Draft assistant running").build()
            ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

            // show the panel first so the user always sees something
            overlay = Overlay(this) { lane -> manualLane = lane; refresh() }
            overlay?.show("Starting…", manualLane, emptyList(), emptyList(), emptyList())

            val code = intent?.getIntExtra("code", 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
                                else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
            if (data == null) throw IllegalStateException("no capture permission data")

            projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, main)

            engine = DraftEngine(HeroData.load(this))
            detector = Detector(this, engine)
            overlay?.show("Waiting for draft screen…", manualLane, emptyList(), emptyList(), emptyList())
            running = true
            bg.post(loop)
        } catch (e: Throwable) {
            val msg = "Start failed: ${e.javaClass.simpleName}: ${e.message}"
            overlay?.show(msg, manualLane, emptyList(), emptyList(), emptyList())
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick() } catch (e: Throwable) {
                val msg = "Error: ${e.javaClass.simpleName}: ${e.message}"
                main.post { overlay?.show(msg, manualLane, emptyList(), emptyList(), emptyList()) }
            }
            bg.postDelayed(this, 900)
        }
    }

    private fun ensureDisplay() {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val scale = minOf(1f, 1600f / maxOf(b.width(), b.height()))
        val w = (b.width() * scale).toInt(); val h = (b.height() * scale).toInt()
        if (w == capW && h == capH && vd != null) return
        vd?.release(); reader?.close()
        capW = w; capH = h
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection!!.createVirtualDisplay("draft", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private fun tick() {
        ensureDisplay()
        val img = reader?.acquireLatestImage() ?: return
        val bmp: Bitmap
        try {
            val p = img.planes[0]
            val pad = p.rowStride - p.pixelStride * img.width
            val full = Bitmap.createBitmap(img.width + pad / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            bmp = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        } finally { img.close() }
        frames++
        lastRes = detector.process(bmp)
        if (lastRes == null) main.post { overlay?.show("No draft screen seen · frames $frames", manualLane, emptyList(), emptyList(), emptyList()) }
        else main.post { refresh() }
    }

    private fun refresh() {
        val res = lastRes
        if (res == null) { overlay?.show("Waiting for draft screen…", manualLane, emptyList(), emptyList(), emptyList()); return }
        val lane = res.state.lane ?: manualLane
        val s = res.state.copy(lane = lane)
        val mode = if (s.enemies.isEmpty()) "FIRST PICK · meta" else "COUNTER PICK · vs ${s.enemies.size}"
        overlay?.show(mode, lane, engine.recommend(s), s.enemies, s.allies, res.debug)
    }

    override fun onDestroy() {
        running = false
        overlay?.remove(); vd?.release(); reader?.close(); projection?.stop(); thread.quitSafely()
        super.onDestroy()
    }
}
