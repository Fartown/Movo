package io.github.fartown.movo.tvcomposeprobe

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.json.JSONObject

class BenchmarkActivity : ComponentActivity() {
    private val frames = mutableListOf<Double>()
    private val thread = HandlerThread("frame-metrics")
    private var started = 0L
    private var droppedReports = 0
    private var focusChanges = 0
    private var variant = "default"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        variant = intent.getStringExtra("variant") ?: "default"
        started = SystemClock.elapsedRealtime()
        thread.start()
        window.addOnFrameMetricsAvailableListener({ _, metrics, dropped ->
            if (SystemClock.elapsedRealtime() - started > 3000) synchronized(frames) {
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                droppedReports += dropped
            }
        }, Handler(thread.looper))
        setContent {
            MaterialTheme {
                val first = remember { FocusRequester() }
                Column(Modifier.fillMaxSize().background(Color(0xFF202024)).padding(36.dp)) {
                    Text("Movo P0 · $variant · 100 项焦点列表", Modifier.padding(bottom = 20.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items((0 until 100).toList(), key = { it }) { index ->
                            var focused by remember { mutableStateOf(false) }
                            val itemModifier = Modifier.fillMaxWidth().height(56.dp)
                                .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged {
                                    focused = it.isFocused
                                    if (it.isFocused) { focusChanges++; Log.i("MovoTvCompose", "FOCUS $index") }
                                }
                            if (variant == "lightweight") {
                                Box(itemModifier.background(if (focused) Color(0xFF354759) else Color(0xFF303034))
                                    .border(2.dp, if (focused) Color.White else Color.Transparent).focusable()
                                    .padding(horizontal = 20.dp), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
                                    Text("项目 ${index + 1} · 方向键移动焦点")
                                }
                            } else {
                                Button(onClick = {}, modifier = itemModifier) { Text("项目 ${index + 1} · 方向键移动焦点") }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { first.requestFocus() }
            }
        }
        Handler(mainLooper).postDelayed({ report("40_seconds") }, 40000)
    }
    private fun report(reason: String) {
        synchronized(frames) {
            val sorted = frames.sorted()
            fun percentile(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
            val result = JSONObject().put("reason", reason).put("variant", variant).put("frames", sorted.size)
                .put("p50_ms", percentile(0.5)).put("p95_ms", percentile(0.95))
                .put("max_ms", sorted.lastOrNull() ?: 0.0).put("over_16_67ms", sorted.count { it > 16.67 })
                .put("focus_changes", focusChanges).put("dropped_metric_reports", droppedReports)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("release", true)
            Log.i("MovoTvCompose", "METRICS $result")
        }
    }
    override fun onPause() { report("pause"); super.onPause() }
    override fun onDestroy() { thread.quitSafely(); super.onDestroy() }
}
