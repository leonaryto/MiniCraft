package com.minicraft.mcserver.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.minicraft.mcserver.R
import com.minicraft.mcserver.server.ServerLauncher
import com.minicraft.mcserver.server.ServerProvisioner

/**
 * Pestana "Consola": terminal oscuro con el log del servidor y envio de comandos.
 *
 * Solo frontend: reutiliza [ServerLauncher.sendCommand] y el buffer global [ConsoleLog]
 * que ya alimentaba la pantalla anterior. Los chips de comandos rapidos son los mismos
 * que ya se usaban (list, tps, save-all, time set day, weather clear, help, stop).
 */
class ConsoleActivity : NavActivity() {

    override val titleRes = R.string.nav_console
    override val subtitleRes = R.string.subtitle_console
    override val tabId = R.id.tabConsole

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tvTerminal: TextView
    private lateinit var scrollTerminal: ScrollView
    private lateinit var cmdInput: EditText

    private val listener: (String) -> Unit = { line ->
        ui.post {
            tvTerminal.append(line + "\n")
            scrollTerminal.post { scrollTerminal.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.MinicraftTheme)
        setContentView(R.layout.activity_console)
        setupChrome()

        tvTerminal = findViewById(R.id.tvTerminal)
        scrollTerminal = findViewById(R.id.scrollTerminal)
        cmdInput = findViewById(R.id.cmdInput)

        // Pintar el buffer historico (consola vacia si el server no ha arrancado).
        val hist = ConsoleLog.snapshot()
        if (hist.isEmpty()) {
            tvTerminal.text = getString(R.string.console_empty)
        } else {
            tvTerminal.text = hist.joinToString("\n")
        }
        scrollTerminal.post { scrollTerminal.fullScroll(View.FOCUS_DOWN) }

        ConsoleLog.addListener { line ->
            ui.post {
                // Quitar el placeholder vacio al llegar la primera linea real.
                if (tvTerminal.text.toString() == getString(R.string.console_empty)) {
                    tvTerminal.text = ""
                }
                tvTerminal.append(line + "\n")
                scrollTerminal.post { scrollTerminal.fullScroll(View.FOCUS_DOWN) }
            }
        }

        // Envio por teclado (Enter) y boton
        cmdInput.setOnEditorActionListener { _, _, _ -> sendCmd(); true }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendCmd() }

        // Chips — mismos comandos existentes
        chip(R.id.chipList, "list")
        chip(R.id.chipTps, "tps")
        chip(R.id.chipSave, "save-all")
        chip(R.id.chipDay, "time set day")
        chip(R.id.chipWeather, "weather clear")
        chip(R.id.chipHelp, "help")
        chip(R.id.chipStop, "stop")
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
    }

    private fun chip(id: Int, cmd: String) {
        findViewById<Button>(id).setOnClickListener {
            cmdInput.setText(cmd)
            sendCmd()
        }
    }

    private fun sendCmd() {
        val c = cmdInput.text.toString().trim()
        if (c.isEmpty()) return
        val ok = ServerLauncher.sendCommand(c)
        ConsoleLog.add(
            if (ok) "→ $c"
            else getString(com.minicraft.mcserver.R.string.console_send_failed, c)
        )
        cmdInput.setText("")
        if (!ok) Toast.makeText(this, com.minicraft.mcserver.R.string.console_not_running, Toast.LENGTH_SHORT).show()
    }
}
