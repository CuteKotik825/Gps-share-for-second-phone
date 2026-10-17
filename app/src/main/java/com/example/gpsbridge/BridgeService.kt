package com.example.gpsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Общее состояние для экрана: статус и признак «работает». */
object Bus {
    @Volatile
    var running = false

    @Volatile
    var status = "Остановлено"

    @Volatile
    var listener: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun post(text: String) {
        status = text
        val l = listener ?: return
        main.post { l(text) }
    }
}

/**
 * Один сервис на обе роли.
 *
 * Отправитель: читает GPS и шлёт точки (Wi-Fi/UDP или Bluetooth/RFCOMM).
 * Получатель: принимает точки и подставляет их системе как фиктивное местоположение.
 *
 * Wi-Fi: получатель раз в 2 секунды шлёт отправителю «GBHELLO|ключ» (по умолчанию на шлюз Wi-Fi,
 * то есть на телефон-точку доступа), отправитель запоминает, откуда пришёл HELLO, и шлёт точки туда.
 * Если задан IP вручную, используется он.
 */
class BridgeService : Service() {

    companion object {
        const val ACTION_START = "com.example.gpsbridge.START"
        const val ACTION_STOP = "com.example.gpsbridge.STOP"
        const val EXTRA_ROLE = "role"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_KEY = "key"
        const val EXTRA_BT_ADDR = "bt_addr"

        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
        private val BT_UUID: UUID = UUID.fromString("3f6a1c2e-7b4d-4a58-9c1e-5d2b8e4f7a10")
    }

    // ---- настройки запуска ----
    private var role = "sender"
    private var transport = "wifi"
    private var ip = ""
    private var port = 45678
    private var key = ""
    private var btAddr = ""

    // ---- состояние ----
    @Volatile
    private var running = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var lm: LocationManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcLock: WifiManager.MulticastLock? = null

    @Volatile
    private var udpSock: DatagramSocket? = null

    @Volatile
    private var btSock: BluetoothSocket? = null

    @Volatile
    private var btServer: BluetoothServerSocket? = null
    private val btLock = Any()
    private var btOut: OutputStream? = null

    private var worker: Thread? = null
    private var helloThread: Thread? = null
    private var locThread: HandlerThread? = null
    private var locListener: LocationListener? = null

    private val peers = ConcurrentHashMap<InetSocketAddress, Long>()

    @Volatile
    private var manualAddr: InetSocketAddress? = null

    @Volatile
    private var lastFix: Fix? = null

    @Volatile
    private var lastFixAt = 0L

    @Volatile
    private var lastFixTime = 0L

    @Volatile
    private var counter = 0L

    @Volatile
    private var link = ""

    @Volatile
    private var problem = ""

    @Volatile
    private var mockProblem = ""

    @Volatile
    private var mockActive = false
    private var mockTouched = false
    private var lastMockTry = 0L
    private var lastNotifAt = 0L

    private val providers = arrayOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (role == "receiver" && !mockActive) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastMockTry > 3000) {
                    lastMockTry = now
                    setupMock()
                }
            }
            publishStatus()
            main.postDelayed(this, 1000)
        }
    }

    // =====================================================================================
    // Жизненный цикл
    // =====================================================================================

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GPS-мост", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        role = intent.getStringExtra(EXTRA_ROLE) ?: "sender"
        transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "wifi"
        ip = intent.getStringExtra(EXTRA_IP) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 45678)
        key = intent.getStringExtra(EXTRA_KEY) ?: ""
        btAddr = intent.getStringExtra(EXTRA_BT_ADDR) ?: ""

        try {
            val notif = buildNotification("Запуск…")
            if (Build.VERSION.SDK_INT >= 29) {
                val type = if (role == "sender") {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIF_ID, notif, type)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Bus.post("Не удалось запустить сервис: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        stopWork()
        startWork()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWork()
        super.onDestroy()
    }

    private fun startWork() {
        running = true
        Bus.running = true
        counter = 0
        lastFix = null
        lastFixAt = 0
        lastFixTime = 0
        link = ""
        problem = ""
        mockProblem = ""
        manualAddr = null
        peers.clear()
        acquireLocks()
        try {
            if (role == "sender") startSender() else startReceiver()
        } catch (e: Exception) {
            problem = e.message ?: e.toString()
        }
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun stopWork() {
        running = false
        Bus.running = false
        main.removeCallbacks(ticker)

        locListener?.let {
            try {
                lm.removeUpdates(it)
            } catch (e: Exception) {
            }
        }
        locListener = null
        locThread?.quitSafely()
        locThread = null

        try {
            udpSock?.close()
        } catch (e: Exception) {
        }
        udpSock = null
        try {
            btSock?.close()
        } catch (e: Exception) {
        }
        btSock = null
        try {
            btServer?.close()
        } catch (e: Exception) {
        }
        btServer = null
        synchronized(btLock) { btOut = null }

        worker?.interrupt()
        worker = null
        helloThread?.interrupt()
        helloThread = null

        if (mockTouched) {
            for (name in providers) {
                try {
                    lm.removeTestProvider(name)
                } catch (e: Exception) {
                }
            }
            mockTouched = false
        }
        mockActive = false

        releaseLocks()
        Bus.post("Остановлено")
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpsbridge:cpu")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) {
        }
        if (transport == "wifi") {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= 29) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                val wfl = wm.createWifiLock(mode, "gpsbridge:wifi")
                wfl.setReferenceCounted(false)
                wfl.acquire()
                wifiLock = wfl
                val mc = wm.createMulticastLock("gpsbridge:mc")
                mc.setReferenceCounted(false)
                mc.acquire()
                mcLock = mc
            } catch (e: Exception) {
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        try {
            mcLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
        }
        wakeLock = null
        wifiLock = null
        mcLock = null
    }

    private fun launch(block: () -> Unit): Thread {
        val t = Thread { block() }
        t.isDaemon = true
        t.start()
        return t
    }

    // =====================================================================================
    // Отправитель
    // =====================================================================================

    @SuppressLint("MissingPermission")
    private fun startSender() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            problem = "нет разрешения на геолокацию"
            return
        }

        val t = HandlerThread("gpsbridge-send")
        t.start()
        locThread = t

        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try {
                    onFix(location)
                } catch (e: Exception) {
                    problem = "отправка: ${e.message}"
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        locListener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, t.looper)

        if (transport == "wifi") startSenderWifi() else startSenderBt()
    }

    private fun onFix(location: Location) {
        val fix = Fix.fromLocation(location)
        lastFix = fix
        lastFixAt = SystemClock.elapsedRealtime()
        val json = fix.toJson(if (transport == "wifi") key else "")
        if (transport == "wifi") sendUdp(json) else sendBt(json)
    }

    private fun startSenderWifi() {
        worker = launch {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(port))
                udpSock = sock

                if (ip.isNotEmpty()) {
                    try {
                        manualAddr = InetSocketAddress(InetAddress.getByName(ip), port)
                    } catch (e: Exception) {
                        problem = "неверный IP: $ip"
                    }
                }

                val buf = ByteArray(512)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (msg == "GBHELLO|$key") {
                        peers[InetSocketAddress(p.address, p.port)] = SystemClock.elapsedRealtime()
                    }
                }
            } catch (e: Exception) {
                if (running) problem = "Wi-Fi: ${e.message}"
            }
        }
    }

    private fun sendUdp(json: String) {
        val sock = udpSock ?: return
        val data = json.toByteArray(Charsets.UTF_8)
        val now = SystemClock.elapsedRealtime()

        val targets = LinkedHashSet<InetSocketAddress>()
        for ((addr, seen) in peers) {
            if (now - seen < 10000) targets.add(addr) else peers.remove(addr)
        }
        manualAddr?.let { targets.add(it) }

        if (targets.isEmpty()) {
            link = "Wi-Fi: жду получателя (он сам найдёт отправителя через точку доступа)"
            return
        }

        var sent = 0
        for (addr in targets) {
            try {
                sock.send(DatagramPacket(data, data.size, addr))
                sent++
            } catch (e: IOException) {
                problem = "Wi-Fi: ${e.message}"
            }
        }
        if (sent > 0) {
            counter++
            link = "Wi-Fi: получателей ${targets.size}"
        }
    }

    private fun startSenderBt() {
        if (!BluetoothAdapter.checkBluetoothAddress(btAddr)) {
            problem = "не выбран получатель Bluetooth"
            return
        }
        val adapter: BluetoothAdapter? =
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        worker = launch {
            while (running) {
                var s: BluetoothSocket? = null
                try {
                    val ad = adapter
                    if (ad == null || !ad.isEnabled) {
                        link = "BT: Bluetooth выключен"
                        Thread.sleep(2000)
                        continue
                    }
                    link = "BT: подключаюсь…"
                    val dev = ad.getRemoteDevice(btAddr)
                    val sock = dev.createRfcommSocketToServiceRecord(BT_UUID)
                    s = sock
                    btSock = sock
                    sock.connect()
                    synchronized(btLock) { btOut = sock.outputStream }
                    link = "BT: подключено"
                    val ins = sock.inputStream
                    // получатель ничего не шлёт: read вернётся (или бросит), только если связь оборвалась
                    while (running && ins.read() != -1) {
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) link = "BT: нет связи (${e.message ?: "ошибка"}), повтор…"
                } finally {
                    synchronized(btLock) { btOut = null }
                    try {
                        s?.close()
                    } catch (e: IOException) {
                    }
                }
                if (running) {
                    try {
                        Thread.sleep(2000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun sendBt(json: String) {
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        synchronized(btLock) {
            val out = btOut ?: return
            try {
                out.write(bytes)
                out.flush()
                counter++
            } catch (e: IOException) {
                btOut = null
                try {
                    btSock?.close()
                } catch (e2: IOException) {
                }
            }
        }
    }

    // =====================================================================================
    // Получатель
    // =====================================================================================

    private fun startReceiver() {
        lastMockTry = SystemClock.elapsedRealtime()
        setupMock()
        if (transport == "wifi") startReceiverWifi() else startReceiverBt()
    }

    private fun setupMock(): Boolean {
        try {
            mockTouched = true
            for (name in providers) {
                try {
                    lm.removeTestProvider(name)
                } catch (e: Exception) {
                }
                lm.addTestProvider(
                    name,
                    false,
                    name == LocationManager.GPS_PROVIDER,
                    false,
                    false,
                    true,
                    true,
                    true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(name, true)
            }
            mockActive = true
            mockProblem = ""
            return true
        } catch (e: SecurityException) {
            mockProblem = "нет прав на подмену местоположения. Выбери «GPS-мост» в настройках " +
                "разработчика («Выбрать приложение для фиктивных местоположений») " +
                "или нажми «Выдать права mock через root»"
        } catch (e: Exception) {
            mockProblem = "mock-провайдер: ${e.message}"
        }
        for (name in providers) {
            try {
                lm.removeTestProvider(name)
            } catch (e: Exception) {
            }
        }
        mockActive = false
        return false
    }

    private fun startReceiverWifi() {
        worker = launch {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.bind(InetSocketAddress(port))
                udpSock = sock
                startHello(sock)

                val buf = ByteArray(1024)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val msg = String(p.data, 0, p.length, Charsets.UTF_8)
                    if (msg.startsWith("{")) handleLine(msg)
                }
            } catch (e: Exception) {
                if (running) problem = "Wi-Fi: ${e.message}"
            }
        }
    }

    private fun startHello(sock: DatagramSocket) {
        helloThread = launch {
            val hello = "GBHELLO|$key".toByteArray(Charsets.UTF_8)
            var manual: InetSocketAddress? = null
            if (ip.isNotEmpty()) {
                try {
                    manual = InetSocketAddress(InetAddress.getByName(ip), port)
                } catch (e: Exception) {
                    problem = "неверный IP: $ip"
                }
            }
            while (running) {
                try {
                    val target: InetSocketAddress? = manual ?: gatewayAddress()?.let { InetSocketAddress(it, port) }
                    if (target != null) {
                        sock.send(DatagramPacket(hello, hello.size, target))
                        link = "Wi-Fi: ищу отправителя на ${target.address.hostAddress}"
                    } else {
                        link = "Wi-Fi: нет подключения к точке доступа (или укажи IP отправителя)"
                    }
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!running) break
                    link = "Wi-Fi: ошибка HELLO: ${e.message}"
                    try {
                        Thread.sleep(2000)
                    } catch (e2: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun gatewayAddress(): InetAddress? {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (r in lp.routes) {
                    val gw = r.gateway
                    if (r.isDefaultRoute && gw is Inet4Address && !gw.isAnyLocalAddress) return gw
                }
            }
        } catch (e: Exception) {
        }
        return null
    }

    private fun startReceiverBt() {
        val adapter: BluetoothAdapter? =
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        worker = launch {
            while (running) {
                var server: BluetoothServerSocket? = null
                try {
                    val ad = adapter
                    if (ad == null || !ad.isEnabled) {
                        link = "BT: Bluetooth выключен"
                        Thread.sleep(2000)
                        continue
                    }
                    val srv = ad.listenUsingRfcommWithServiceRecord("GPSBridge", BT_UUID)
                    server = srv
                    btServer = srv
                    while (running) {
                        link = "BT: жду подключения отправителя…"
                        val s = srv.accept()
                        btSock = s
                        link = "BT: отправитель подключён"
                        try {
                            val br = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
                            while (running) {
                                val line = br.readLine() ?: break
                                handleLine(line)
                            }
                        } catch (e: IOException) {
                            // связь оборвалась, ждём нового подключения
                        } finally {
                            try {
                                s.close()
                            } catch (e: IOException) {
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) link = "BT: ошибка (${e.message ?: "?"}), повтор…"
                } finally {
                    try {
                        server?.close()
                    } catch (e: IOException) {
                    }
                }
                if (running) {
                    try {
                        Thread.sleep(2000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun handleLine(line: String) {
        val f = Fix.fromJson(line, if (transport == "wifi") key else "") ?: return
        val now = SystemClock.elapsedRealtime()
        // отбрасываем запоздавшие/повторные пакеты; если данных не было давно, принимаем любые
        if (f.time <= lastFixTime && now - lastFixAt < 30000) return
        lastFixTime = f.time
        lastFix = f
        lastFixAt = now
        counter++
        pushMock(f)
    }

    private fun pushMock(f: Fix) {
        if (!mockActive) return
        val nowMs = System.currentTimeMillis()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        for (name in providers) {
            try {
                val loc = Location(name)
                loc.latitude = f.lat
                loc.longitude = f.lon
                f.alt?.let { loc.altitude = it }
                f.speed?.let { loc.speed = it }
                f.bearing?.let { loc.bearing = it }
                loc.accuracy = f.accuracy ?: 5f
                loc.time = nowMs
                loc.elapsedRealtimeNanos = nowNs
                f.satellites?.let { sats ->
                    val b = Bundle()
                    b.putInt("satellites", sats)
                    loc.extras = b
                }
                lm.setTestProviderLocation(name, loc)
            } catch (e: Exception) {
                mockProblem = "не удалось передать координаты: ${e.message}"
                mockActive = false // ticker попробует настроить заново
            }
        }
    }

    // =====================================================================================
    // Статус и уведомление
    // =====================================================================================

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun publishStatus() {
        val now = SystemClock.elapsedRealtime()
        val tr = if (transport == "wifi") "Wi-Fi" else "Bluetooth"
        val f = lastFix
        val sb = StringBuilder()

        if (role == "sender") {
            sb.append("Отправитель · ").append(tr).append('\n')
            if (f == null) {
                sb.append("GPS: ожидание фикса…\n")
            } else {
                sb.append("GPS: ").append(fmt(f.lat)).append(", ").append(fmt(f.lon)).append('\n')
                val acc = f.accuracy?.let { String.format(Locale.US, "%.0f", it) } ?: "?"
                val sats = f.satellites?.toString() ?: "?"
                sb.append("точность ").append(acc).append(" м, спутников ").append(sats)
                    .append(", фикс ").append((now - lastFixAt) / 1000).append(" с назад\n")
            }
            sb.append("Отправлено точек: ").append(counter).append('\n')
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                sb.append("⚠ GPS выключен в настройках телефона\n")
            }
        } else {
            sb.append("Получатель · ").append(tr).append('\n')
            sb.append("Подмена GPS: ").append(if (mockActive) "включена" else "НЕ включена").append('\n')
            sb.append("Получено точек: ").append(counter).append('\n')
            if (f == null) {
                sb.append("Данных от отправителя ещё нет\n")
            } else {
                sb.append("Последняя: ").append((now - lastFixAt) / 1000).append(" с назад\n")
                sb.append(fmt(f.lat)).append(", ").append(fmt(f.lon))
                f.speed?.let { sb.append(String.format(Locale.US, " · %.0f км/ч", it * 3.6f)) }
                sb.append('\n')
            }
            if (mockProblem.isNotEmpty()) sb.append("⚠ ").append(mockProblem).append('\n')
        }

        if (link.isNotEmpty()) sb.append(link).append('\n')
        if (problem.isNotEmpty()) sb.append("⚠ ").append(problem).append('\n')

        Bus.post(sb.toString().trimEnd())

        if (now - lastNotifAt > 5000) {
            lastNotifAt = now
            val short = if (role == "sender") "Отправлено точек: $counter" else "Получено точек: $counter"
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(short))
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = Intent(this, BridgeService::class.java).setAction(ACTION_STOP)
        val stop = PendingIntent.getService(this, 1, stopIntent, flags)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Стоп",
            stop
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if (role == "sender") "GPS-мост: отправка" else "GPS-мост: приём")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(stopAction)
            .build()
    }
}
