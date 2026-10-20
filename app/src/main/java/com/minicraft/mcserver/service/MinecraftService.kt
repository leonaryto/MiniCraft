package com.minicraft.mcserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import com.minicraft.mcserver.AppLocale
import com.minicraft.mcserver.R
import com.minicraft.mcserver.server.JreProvisioner
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.minicraft.mcserver.server.ServerLauncher
import com.minicraft.mcserver.server.ServerProvisioner
import com.minicraft.mcserver.playit.PlayitAgent
import com.minicraft.mcserver.ui.ConsoleLog
import com.minicraft.mcserver.ui.LastServerStore
import java.io.File

/**
 * Mantiene el servidor de Minecraft vivo con la pantalla apagada.
 *
 * Por que `specialUse` y no `dataSync`:
 * `dataSync` esta limitado a 6h/24h desde Android 15 y, sobre todo, su semantica no
 * cubre un servidor de juegos — un servidor no transfiere datos del dispositivo.
 * `specialUse` es lo que corresponde a este caso.
 *
 * WakeLock: Doze "ignores wake locks" segun la documentacion de Android, asi que el
 * WakeLock por si solo no basta. Necesita un AlarmManager periodico como respaldo
 * (ver WakeupScheduler). Esto es exactamente lo que hay que probar en el telefono.
*/
class MinecraftService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var playitAgent: PlayitAgent
    private var thread: Thread? = null
    @Volatile private var stopping = false
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val TAG = "MinicraftService"
        const val CHANNEL_ID = "minicraft_server"
        const val NOTIF_ID = 1001
        const val EXTRA_ACTION = "action"     // "start" | "stop" | "cmd" | "install"
        const val EXTRA_CMD = "cmd"           // linea a escribir en la consola
        const val EXTRA_VERSION = "version"   // p.ej. "1.21.1"
        const val EXTRA_FLAVOR = "flavor"     // "vanilla" | "fabric"
        /** Accion de verificacion: instala el servidor sin pasar por la UI. */
        const val EXTRA_INSTALL = "install"
        const val EXTRA_MCVERSION = "mcversion"
        /** Ruta ABSOLUTA del directorio del servidor. Tiene prioridad sobre
         *  flavor+version; la usan ServerTestActivity y las pruebas por adb. */
        const val EXTRA_SERVER_DIR = "serverDir"

        /**
         * Directorio y jar del servidor elegido. La UI los pasa al arrancar; si faltan
         * se usa el servidor Fabric ya instalado, que es lo que hacia antes.
         */
        @Volatile var serverDirPath: String? = null
        @Volatile var launchJarName: String = "fabric-server-launch.jar"

        fun start(ctx: Context) {
            val i = Intent(ctx, MinecraftService::class.java).putExtra(EXTRA_ACTION, "start")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MinecraftService::class.java).putExtra(EXTRA_ACTION, "stop"))
        }

    /**
     * Escribe un comando en la consola del servidor, sin necesidad de UI.
     * Pensado para pruebas por adb:
     *   adb shell am startservice -n com.minicraft.mcserver/.service.MinecraftService \
     *       --es action cmd --es cmd "say hola"
     */
        /**
         * Arranca el servidor de [serverDir]/[launchJar]. El directorio debe existir ya
         * (lo crea ServerProvisioner.install).
         *
         * Se pasa el directorio explícitamente como extra (`serverDir`) en vez de
         * confiar en variables del companion, porque la rama `start` del servicio NO lee
         * las del companion cuando el intent no lleva extras: sin esto, la UI siempre
         * arrancaba el servidor por defecto (fabric-1.21.1) aunque se hubiera elegido
         * vanilla-1.20.5. Con el extra, `detectLaunchJar(dir)` deduce el jar correcto.
         */
        fun launch(ctx: Context, serverDir: File, launchJar: String) {
            serverDirPath = serverDir.absolutePath
            launchJarName = launchJar
            val i = Intent(ctx, MinecraftService::class.java)
                .putExtra(EXTRA_ACTION, "start")
                .putExtra(EXTRA_SERVER_DIR, serverDir.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Deduce que jar hay que ejecutar mirando lo que hay en [dir]. */
        private fun detectLaunchJar(dir: File): String =
            when {
                File(dir, "fabric-server-launch.jar").isFile -> "fabric-server-launch.jar"
                File(dir, "server.jar").isFile -> "server.jar"
                else -> "fabric-server-launch.jar"
            }

        fun command(ctx: Context, cmd: String) {
            val i = Intent(ctx, MinecraftService::class.java)
                .putExtra(EXTRA_ACTION, "cmd")
                .putExtra(EXTRA_CMD, cmd)
            ctx.startService(i)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        playitAgent = PlayitAgent.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            "stop" -> { stopServerAsync(); return START_NOT_STICKY }
            "cmd" -> {
                val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty().trim()
                if (cmd.isEmpty()) {
                    Log.w(TAG, "accion cmd sin texto")
                } else {
                    Log.i(TAG, "enviando por consola: $cmd")
                    if (!ServerLauncher.sendCommand(cmd)) Log.w(TAG, "no se pudo enviar: $cmd")
                }
                return START_NOT_STICKY
            }
            "install" -> {
                // Sin esto el sistema mata el proceso a los ~5 s: la descarga del
                // instalador y su ejecucion tardan mas que eso.
                goForeground(getString(R.string.notif_installing))
                val flavor = intent?.getStringExtra(EXTRA_FLAVOR) ?: "fabric"
                val ver = intent?.getStringExtra(EXTRA_VERSION) ?: "1.21.1"
                Thread({
                    // La JRE es requisito de TODO lo demas: sin ella el instalador
                    // de Fabric ni siquiera enlaza ("CANNOT LINK EXECUTABLE:
                    // libjli.so not found"). Si falta (p.ej. tras borrar los
                    // datos de la app), se descarga de Termux aqui mismo.
                    val jreErr = JreProvisioner.ensureInstalled(this) { line ->
                        Log.i(TAG, line)
                        updateNotification(line.take(40))
                    }
                    if (jreErr != null) {
                        Log.e(TAG, "INSTALL-RESPUESTA ok=false :: $jreErr")
                        updateNotification("JRE: $jreErr")
                        stopSelf()
                        return@Thread
                    }
                    val f = if (flavor == "vanilla") {
                        ServerProvisioner.Flavor.VANILLA
                    } else {
                        ServerProvisioner.Flavor.FABRIC
                    }
                    val res = ServerProvisioner.install(this, f, ver) { line ->
                        Log.i(TAG, line)
                    }
                    Log.i(TAG, "INSTALL-RESPUESTA ok=${res.ok} :: ${res.message}")
                    stopSelf()
                }, "install-thread").start()
                return START_NOT_STICKY
            }
            else -> {
                // Resolver que servidor arrancar. Prioridad:
                //   1) ruta explicita (serverDir)  -> pruebas / ServerTestActivity
                //   2) flavor + version             -> lo que manda la UI
                //   3) el de siempre               -> fabric-1.21.1
                val explicitDir = intent?.getStringExtra(EXTRA_SERVER_DIR)
                val fl = intent?.getStringExtra(EXTRA_FLAVOR)
                val ver = intent?.getStringExtra(EXTRA_VERSION)
                    ?: intent?.getStringExtra(EXTRA_MCVERSION)

                if (!explicitDir.isNullOrBlank()) {
                    serverDirPath = explicitDir
                    launchJarName = detectLaunchJar(File(explicitDir))
                } else if (fl != null && fl.isNotBlank() && ver != null && ver.isNotBlank()) {
                    val f = if (fl.equals("vanilla", true)) {
                        ServerProvisioner.Flavor.VANILLA
                    } else {
                        ServerProvisioner.Flavor.FABRIC
                    }
                    val dir = ServerProvisioner.dirFor(this, f, ver)
                    val jar = ServerProvisioner.launchJarFor(this, f, ver)
                    if (jar == null) {
                        Log.e(TAG, "$fl $ver no esta instalado; pulsa Instalar primero")
                        updateNotification(getString(R.string.notif_install_server_first))
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    serverDirPath = dir.absolutePath
                    launchJarName = jar
                } else {
                    // Reinicio por START_STICKY o por la alarma: no hay extras.
                    // Retomamos el ultimo servidor lanzado, no un flavor fijo.
                    val last = LastServerStore.load(this)
                    if (last != null) {
                        serverDirPath = last.dir
                        launchJarName = detectLaunchJar(File(last.dir))
                        Log.i(TAG, "retomando ultimo servidor: ${last.dir}")
                    } else {
                        val f = ServerProvisioner.Flavor.FABRIC
                        val fallbackVersion = "1.21.1"
                        val jar = ServerProvisioner.launchJarFor(this, f, fallbackVersion)
                        if (jar == null) {
                            Log.e(TAG, "no hay servidor recordado ni Fabric 1.21.1 instalado")
                            updateNotification(getString(R.string.notif_install_server_first))
                            stopSelf()
                            return START_NOT_STICKY
                        }
                        serverDirPath =
                            ServerProvisioner.dirFor(this, f, fallbackVersion).absolutePath
                        launchJarName = jar
                    }
                }
                startServer()
                return START_STICKY
            }
        }
    }

    /**
     * El usuario deslizo la app fuera de Recientes. Es la forma intencional de
     * cerrarla: paramos el servidor de forma ordenada y cancelamos la alarma,
     * para que no se reinicie por detras. Si el sistema mata el proceso sin
     * deslizar, START_STICKY y la alarma siguen siendo el respaldo.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "tarea eliminada de Recientes; deteniendo el servidor")
        stopServerAsync()
        super.onTaskRemoved(rootIntent)
    }

    private fun startServer() {
        // 1) Notificacion obligatoria para un FGS. Se crea ANTES de startForeground.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_desc) }
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MinecraftService::class.java).putExtra(EXTRA_ACTION, "stop"),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notifText = if (ServerLauncher.running && ServerLauncher.ready) {
            val s = (System.currentTimeMillis() - ServerLauncher.readyAtMs) / 1000.0
            getString(R.string.notif_ready, "%.0f".format(s))
        } else {
            getString(R.string.notif_text_running)
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(notifText)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                null, getString(R.string.notif_action_stop), stopIntent).build())
            .build()

        // startForeground en el hilo principal, sin ANR.
        startForeground(NOTIF_ID, notif)
        Log.i(TAG, "FGS iniciado (specialUse)")

        if (stopping) {
            Log.w(TAG, "hay una parada en curso; no arranco el servidor")
            stopForeground(true)
            return
        }
        if (ServerLauncher.running || thread?.isAlive == true) {
            Log.w(TAG, "el servidor ya esta corriendo")
            return
        }

        // 2) WakeLock: mantiene la CPU despierta. Doze lo ignora, pero evita que
        //    el sistema mate el proceso por inactividad antes de entrar en Doze.
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Minicraft::Server").apply {
                setReferenceCounted(false)
                acquire()   // sin timeout: el servicio lo libera al parar
            }
            Log.i(TAG, "WakeLock adquirido")
        }

        // 3) Backstop para Doze: alarma periodica que despierta el servicio.
        WakeupScheduler.schedule(this)

        // 4) Lanzar el servidor en un hilo aparte.
        // Leer configuración guardada por ServerConfigSheet para heap y view/sim distance.
        val cfg = com.minicraft.mcserver.ui.ServerConfigSheet.load(this)
        val heapMb = cfg.heapMb
        val srvDir = serverDirPath?.let { File(it) }
            ?: LastServerStore.load(this)?.let { File(it.dir) }
        if (srvDir == null || !srvDir.isDirectory) {
            Log.e(TAG, "el directorio del servidor no existe: ${srvDir?.absolutePath}")
            updateNotification(getString(R.string.notif_install_server_first))
            stopSelf()
            return
        }
        serverDirPath = srvDir.absolutePath
        if (launchJarName.isBlank()) launchJarName = detectLaunchJar(srvDir)
        Log.i(TAG, "servidor elegido: ${srvDir.name} jar=$launchJarName")
        // Generar/actualizar server.properties con view/sim distance elegidos (latencia).
        generateServerProperties(srvDir, cfg)
        val jreHome = File(filesDir, "jre")
        val logOut = File(filesDir, "diag/server-run.log").apply { parentFile?.mkdirs() }
        val t0 = System.currentTimeMillis()

        thread = Thread({
            Log.i(TAG, "hilo del servidor iniciado; dir=$srvDir")
            try {
                ServerLauncher.start(
                    ctx = this,
                    jreHome = jreHome,
                    serverDir = srvDir,
                    launchJar = launchJarName,
                    heapMb = heapMb,
                    onLine = { line ->
                        Log.i("MCLOG", line)
                        // Alimentar el buffer global: de aqui beben la consola (Home y
                        // pestana Consola), el contador de jugadores y el parser de TPS.
                        ConsoleLog.add(line)
                        // Log persistente: permite comprobar tras apagar la pantalla
                        // si el servidor siguio vivo o murio.
                        try {
                            logOut.appendText(line + "\n")
                        } catch (_: Throwable) {}
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "error en el hilo del servidor", t)
                updateNotification(getString(R.string.notif_error, t.message))
            }
        }, "server-thread").also { it.start() }

        // Vigilante: actualiza la notificacion cuando el servidor queda listo y
        // detecta si muere con la pantalla apagada. Espera a que ServerLauncher
        // marque running=true: el hilo del servidor compite con este al arrancar.
        Thread({
            var waited = 0
            while (!ServerLauncher.running && waited < 10_000) {
                if (stopping) return@Thread
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@Thread }
                waited += 100
            }
            if (!ServerLauncher.running) {
                Log.e(TAG, "el servidor no llego a arrancar")
                shutdownAfterServerExit()
                return@Thread
            }

            var announced = false
            while (ServerLauncher.running) {
                if (ServerLauncher.ready && !announced) {
                    announced = true
                    val s = (ServerLauncher.readyAtMs - t0) / 1000.0
                    updateNotification(getString(R.string.notif_ready, "%.0f".format(s)))
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
            if (announced) {
                Log.i(TAG, "el servidor termino: exit=${ServerLauncher.exitCode}")
                updateNotification(getString(R.string.notif_stopped, ServerLauncher.exitCode))
            }
            // Sin servidor no hace falta mantener el proceso en primer plano.
            // Si la parada fue pedida por el usuario, [stopping] ya esta activo.
            shutdownAfterServerExit()
        }, "server-watch").also { it.isDaemon = true }.start()

        // 5) Daemon playitd — tunel VPN hacia 127.0.0.1:25565. Se inicia en paralelo
        //    al servidor; el daemon se conecta a playit.gg en ~2-3s. Solo si el
        //    usuario no lo desactivo en Ajustes (playitEnabled); apagado = solo LAN.
        if (cfg.playitEnabled) {
            playitAgent.start()
        } else {
            Log.i(TAG, "playit desactivado en Ajustes: servidor solo LAN")
        }
    }

    /**
     * Declara el servicio en primer plano. OBLIGATORIO en cuanto se arranca un FGS en
     * Android 8+: sin esto el sistema mata el proceso a los ~5 s con
     * "did not then call Service.startForeground()".
     *
     * Lo usan tanto `startServer()` como la accion `install`: la instalacion de Fabric
     * tambien tarda mas de 5 s y sin esto moria a mitad.
     */
    private fun goForeground(texto: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_desc) }
            )
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    /**
     * Genera/actualiza server.properties con los valores de distancia elegidos
     * por el usuario en ServerConfigSheet. Reduce la carga CPU/tick → menor latencia.
     * NO sobreescribe configuración del jugador;
     * solo actualiza los campos de optimización de latencia.
     */
    private fun generateServerProperties(serverDir: File, cfg: com.minicraft.mcserver.ui.ServerConfigSheet.Config) {
        val f = File(serverDir, "server.properties")
        val map = LinkedHashMap<String, String>()
        if (f.isFile) {
            f.forEachLine { line ->
                val idx = line.indexOf('=')
                if (idx > 0 && !line.startsWith("#")) {
                    map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
        }
        map["view-distance"] = cfg.viewDistance.toString()
        map["simulation-distance"] = cfg.simulationDistance.toString()
        map["network-compression-threshold"] = "512"
        map["max-tick-time"] = "60000"
        map.putIfAbsent("max-players", "4")
        f.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        Log.i(TAG, "server.properties actualizado (view=${cfg.viewDistance}, sim=${cfg.simulationDistance})")
    }

    /**
     * Parada pedida por el usuario o por Recientes. El trabajo pesado (esperar a
     * que el servidor guarde) va en un hilo aparte para no bloquear el hilo
     * principal del servicio.
     */
    private fun stopServerAsync() {
        if (stopping) return
        stopping = true
        Thread({
            try {
                stopServerBlocking()
            } catch (t: Throwable) {
                Log.e(TAG, "error al detener el servidor", t)
            } finally {
                mainHandler.post {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }, "server-stop").start()
    }

    private fun stopServerBlocking() {
        Log.i(TAG, "deteniendo servicio")
        // Preferencia por la consola: `stop` graba los chunks y devuelve exit=0, mientras
        // que SIGTERM mata el proceso por señal y puede dejar el mundo a medias. Solo si
        // el servidor no acepta comandos caemos al SIGTERM.
        val orderly = try { ServerLauncher.sendCommand("stop") } catch (_: Throwable) { false }
        if (orderly) {
            Thread.sleep(6000)      // margen para que guarde y cierre
        }
        ServerLauncher.stop()        // SIGTERM, idempotente si ya salio
        Thread.sleep(1500)
        cleanupResources()
    }

    /** El servidor murio solo: no hay que enviarle `stop`, solo limpiar y salir. */
    private fun shutdownAfterServerExit() {
        if (stopping) return
        stopping = true
        Thread({
            try {
                cleanupResources()
            } catch (t: Throwable) {
                Log.e(TAG, "error al cerrar el servicio", t)
            } finally {
                mainHandler.post {
                    stopForeground(true)
                    stopSelf()
                }
            }
        }, "server-exit").start()
    }

    private fun cleanupResources() {
        ServerLauncher.shutdown()
        playitAgent.stop()  // Detener daemon playitd
        thread?.interrupt()
        thread = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        WakeupScheduler.cancel(this)
    }

    override fun onDestroy() {
        if (!stopping) {
            // El sistema destruyo el servicio sin pasar por una parada ordenada:
            // no dejamos un servidor huerfano. START_STICKY lo relanzara limpio.
            ServerLauncher.stop()
            ServerLauncher.shutdown()
        }
        playitAgent.stop()  // Asegurar que el daemon para si el servicio se destruye
        wakeLock?.let { if (it.isHeld) it.release() }
        Log.i(TAG, "servicio destruido")
        super.onDestroy()
    }
}

/**
 * Alarma periodica: Doze "ignores wake locks", asi que el WakeLock por si solo no
 * mantiene el servicio vivo. Esta alarma es el respaldo.
*/
object WakeupScheduler {
    private const val TAG = "MinicraftWakeup"
    private const val REQ = 4201
    // Intervalo minimo que respeta Android para alarmas en background.
    const val INTERVAL_MS = 15 * 60 * 1000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val i = android.content.Intent(ctx, WakeupReceiver::class.java)
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, REQ, i, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am.setInexactRepeating(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS, pi
        )
        Log.i(TAG, "alarma de respaldo programada cada ${INTERVAL_MS / 60000} min")
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val i = android.content.Intent(ctx, WakeupReceiver::class.java)
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, REQ, i, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }
}

class WakeupReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i("MinicraftWakeup", "alarma disparada; reintentando foreground")
        try {
            MinecraftService.start(ctx)
        } catch (t: Throwable) {
            // Android 12+ puede bloquear el arranque de un FGS desde background.
            // La alarma se reintenta; no dejamos que el receiver crashee.
            Log.w("MinicraftWakeup", "no se pudo relanzar el servicio: ${t.message}")
        }
    }
}