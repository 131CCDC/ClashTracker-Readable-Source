package dev.clashaiaa.installer

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Three buttons, one log. Every action runs off the main thread because each one
 * shells out to `su`.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-installer").apply { isDaemon = true }
    }

    private lateinit var logView: TextView
    private lateinit var buttons: List<Button>
    private val installer by lazy { RootInstaller(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        buttons = listOf(
            findViewById(R.id.inspectButton),
            findViewById(R.id.installButton),
            findViewById(R.id.restoreButton),
            findViewById(R.id.receiptButton),
        )
        findViewById<Button>(R.id.inspectButton).setOnClickListener { run("Preflight") { installer.inspect() } }
        findViewById<Button>(R.id.installButton).setOnClickListener { run("Install") { installer.install() } }
        findViewById<Button>(R.id.restoreButton).setOnClickListener { run("Restore") { installer.restore() } }
        findViewById<Button>(R.id.receiptButton).setOnClickListener { run("Receipt") { installer.receipt() } }

        logView.text = getString(R.string.intro)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun run(title: String, action: () -> String) {
        buttons.forEach { it.isEnabled = false }
        logView.text = "$title ..."
        worker.execute {
            val result = try {
                action()
            } catch (exc: Throwable) {
                "FAIL  $title  ${exc.javaClass.simpleName}: ${exc.message}"
            }
            handler.post {
                logView.text = result
                buttons.forEach { it.isEnabled = true }
            }
        }
    }
}
