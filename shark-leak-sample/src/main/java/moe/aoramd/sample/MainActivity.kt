package moe.aoramd.sample

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.Button
import shark.HeapAnalysisSuccess
import shark.HeapAnalyzer
import shark.HeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakingObjectFinder
import shark.OnAnalysisProgressListener
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.execute).setOnClickListener {
            thread {
                createHugeHprof()?.also {
                    executor.execute {
                        startStressTest(it)
                    }
                }
            }
        }
    }

    companion object {

        private const val TAG = "SharkLeakSample"

        private val executor = Executors.newSingleThreadExecutor {
            Thread(it, "analyze-daemon")
        }

        /**
         * Creates an huge HPROF file and return the file path.
         */
        private fun Context.createHugeHprof(): File? {
            val file = File(cacheDir, "huge.hprof").apply {
                if (exists()) delete()
            }
            Log.i(TAG, "Allocate memory.")
            val container = mutableListOf<Leaking>().apply {
                repeat(20) {
                    add(Leaking(ByteArray(1024 * 1024 * 10)))
                }
            }
            Log.i(TAG, "Dump HPROF file.")
            Debug.dumpHprofData(file.absolutePath)
            Log.i(TAG, "Clear memory.")
            container.clear()
            Runtime.getRuntime().gc()
            return if (file.length() > 0) file else null
        }

        /**
         * Starts a Shark analyzing stress test.
         */
        private fun Context.startStressTest(analyzedHprof: File) {
            repeat(10) { counter ->
                Log.i(TAG, "Stress testing. Counter: $counter")
                val analysis = HeapAnalyzer(OnAnalysisProgressListener.NO_OP)
                    .analyze(analyzedHprof, analyzedHprof.openHeapGraph(), LeakFinder)
                if (analysis is HeapAnalysisSuccess) {
                    analysis.allLeaks.forEach {
                        Log.v(TAG, "Leak found: $it")
                    }
                } else {
                    Log.e(TAG, "Analyze failed.")
                }
            }
            val file = File(cacheDir, "leak.hprof").apply {
                if (exists()) delete()
            }
            Log.i(TAG, "Dump HPROF file for Shark leaking to ${file.absolutePath}.")
            Debug.dumpHprofData(file.absolutePath)
        }
    }
}

private class Leaking(private val array: ByteArray)

private object LeakFinder : LeakingObjectFinder {
    override fun findLeakingObjectIds(graph: HeapGraph): Set<Long> {
        return graph.instances
            .filter { it.instanceClassName == Leaking::class.java.name }
            .map { it.objectId }
            .toSet()
    }
}
