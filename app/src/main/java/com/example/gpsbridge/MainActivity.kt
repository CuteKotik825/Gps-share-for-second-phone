package com.example.gpsbridge

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rgRole: RadioGroup
    private lateinit var rgTransport: RadioGroup
    private lateinit var boxWifi: View
    private lateinit var boxBt: View
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var etKey: EditText
    private lateinit var tvMyIp: TextView
    private lateinit var spBt: Spinner
    private lateinit var btnBtRefresh: Button
    private lateinit var tvBtHint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnRootMock: Button
    private lateinit var btnDevSettings: Button
    private lateinit var btnScreenOff: Button
    private lateinit var btnBattery: Button
    private lateinit var tvStatus: TextView

    private val btNames = ArrayList<String>()
    private val btAddrs = ArrayList<String>()

    private val isSender: Boolean
        get() = rgRole.checkedRadioButtonId == R.id.rbSender

    private val isWifi: Boolean
        get() = rgTransport.checkedRadioButtonId == R.id.rbWifi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("gpsbridge", Context.MODE_PRIVATE)

        rgRole = findViewById(R.id.rgRole)
        rgTransport = findViewById(R.id.rgTransport)
        boxWifi = findViewById(R.id.boxWifi)
        boxBt = findViewById(R.id.boxBt)
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        etKey = findViewById(R.id.etKey)
        tvMyIp = findViewById(R.id.tvMyIp)
        spBt = findViewById(R.id.spBt)
        btnBtRefresh = findViewById(R.id.btnBtRefresh)
        tvBtHint = findViewById(R.id.tvBtHint)
        btnStart = findViewById(R.id.btnStart)
        btnRootMock = findViewById(R.id.btnRootMock)
        btnDevSettings = findViewById(R.id.btnDevSettings)
        btnScreenOff = findViewById(R.id.btnScreenOff)
        btnBattery = findViewById(R.id.btnBattery)
        tvStatus = findViewById(R.id.tvStatus)

        // Android 15+ рисует приложение под системными панелями: добавляем отступы
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = i.top
                bottom = i.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(0, top, 0, bottom)
            insets
        }

        rgRole.check(if (prefs.getString("role", "sender") == "receiver") R.id.rbReceiver else R.id.rbSender)
        rgTransport.check(if (prefs.getString("transport", "wifi") == "bt") R.id.rbBt else R.id.rbWifi)
        etIp.setText(prefs.getString("ip", ""))
        etPort.setText(prefs.getInt("port", 45678).toString())
        etKey.setText(prefs.getString("key", ""))

        rgRole.setOnCheckedChangeListener { _, _ -> updateUi() }
        rgTransport.setOnCheckedChangeListener { _, _ -> updateUi() }
        btnBtRefresh.setOnClickListener { refreshBt() }
        btnStart.setOnClickListener { onStartStop() }
        btnRootMock.setOnClickListener { grantMockViaRoot() }
        btnDevSettings.setOnClickListener { openDevSettings() }
        btnScreenOff.setOnClickListener { screenOffViaRoot() }
        btnBattery.setOnClickListener { requestBatteryWhitelist() }

        updateUi()
    }

    override fun onStart() {
        super.onStart()
        Bus.listener = { s ->
            tvStatus.text = s
            refreshStartButton()
        }
        tvStatus.text = Bus.status
        refreshStartButton()
    }

    override fun onStop() {
        Bus.listener = null
        super.onStop()
    }

    // ---------------------------------------------------------------------------------
    // Интерфейс
    // ---------------------------------------------------------------------------------

    private fun updateUi() {
        val sender = isSender
        val wifi = isWifi

        boxWifi.visibility = if (wifi) View.VISIBLE else View.GONE
        boxBt.visibility = if (wifi) View.GONE else View.VISIBLE
        spBt.visibility = if (sender) View.VISIBLE else View.GONE
        btnBtRefresh.visibility = if (sender) View.VISIBLE else View.GONE

        tvBtHint.text = if (sender) {
            "Выбери получателя из сопряжённых устройств. Если его нет в списке, " +
                "сначала сопрягите телефоны в настройках Bluetooth."
        } else {
            "Получатель ждёт подключения отправителя. Сначала сопрягите телефоны " +
                "в настройках Bluetooth."
        }
        etIp.hint = if (sender) {
            "IP получателя (необязательно)"
        } else {
            "IP отправителя (необязательно; иначе — точка доступа)"
        }
        tvMyIp.text = "Мои IP: " + localIps()

        btnRootMock.visibility = if (sender) View.GONE else View.VISIBLE
        btnDevSettings.visibility = if (sender) View.GONE else View.VISIBLE
        btnScreenOff.visibility = if (sender) View.VISIBLE else View.GONE

        if (sender && !wifi && btNames.isEmpty()) refreshBt()
        refreshStartButton()
    }

    private fun refreshStartButton() {
        btnStart.text = if (Bus.running) "Остановить" else "Запустить"
    }

    private fun localIps(): String {
        val list = ArrayList<String>()
        try {
            val e = NetworkInterface.getNetworkInterfaces()
            while (e != null && e.hasMoreElements()) {
                val ni = e.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address) {
                        val h = a.hostAddress
                        if (h != null) list.add(h)
                    }
                }
            }
        } catch (e: Exception) {
        }
        return if (list.isEmpty()) "нет" else list.joinToString(", ")
    }

    private fun port(): Int {
        val p = etPort.text.toString().trim().toIntOrNull()
        return if (p != null && p in 1024..65535) p else 45678
    }

    private fun selectedBtAddr(): String? {
        val i = spBt.selectedItemPosition
        return if (i >= 0 && i < btAddrs.size) btAddrs[i] else null
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("role", if (isSender) "sender" else "receiver")
            .putString("transport", if (isWifi) "wifi" else "bt")
            .putString("ip", etIp.text.toString().trim())
            .putInt("port", port())
            .putString("key", etKey.text.toString().trim())
            .putString("bt_addr", selectedBtAddr() ?: prefs.getString("bt_addr", ""))
            .apply()
    }

    // ---------------------------------------------------------------------------------
    // Bluetooth-список
    // ---------------------------------------------------------------------------------

    private fun refreshBt() {
        btNames.clear()
        btAddrs.clear()
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth недоступен на этом телефоне", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
            return
        }
        try {
            for (d in adapter.bondedDevices) {
                btNames.add((d.name ?: "?") + " (" + d.address + ")")
                btAddrs.add(d.address)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Нет разрешения на Bluetooth", Toast.LENGTH_LONG).show()
        }
        val ad = ArrayAdapter(this, android.R.layout.simple_spinner_item, btNames)
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spBt.adapter = ad
        val idx = btAddrs.indexOf(prefs.getString("bt_addr", ""))
        if (idx >= 0) spBt.setSelection(idx)
    }

    // ---------------------------------------------------------------------------------
    // Запуск / остановка
    // ---------------------------------------------------------------------------------

    private fun neededPermissions(): Array<String> {
        val list = ArrayList<String>()
        if (isSender) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31 && !isWifi) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        return list
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()
    }

    /** Без этих разрешений работать нельзя (уведомления — по желанию). */
    private fun hardMissing(): List<String> {
        val list = ArrayList<String>()
        if (isSender) list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31 && !isWifi) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        return list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun onStartStop() {
        if (Bus.running) {
            stopService(Intent(this, BridgeService::class.java))
            return
        }
        val needed = neededPermissions()
        val hard = hardMissing()
        if (hard.isNotEmpty() || (needed.isNotEmpty() && !prefs.getBoolean("asked_perm", false))) {
            prefs.edit().putBoolean("asked_perm", true).apply()
            requestPermissions(needed, 100)
            return
        }
        startBridge()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hardMissing().isEmpty()) {
                startBridge()
            } else {
                tvStatus.text = "Нужны разрешения: геолокация (для отправителя) и Bluetooth (для режима Bluetooth). " +
                    "Выдай их в настройках приложения."
            }
        } else if (requestCode == 101) {
            refreshBt()
        }
    }

    private fun startBridge() {
        if (isSender && !isWifi && selectedBtAddr() == null) {
            tvStatus.text = "Выбери получателя в списке Bluetooth (телефоны должны быть сопряжены)"
            return
        }
        savePrefs()
        val i = Intent(this, BridgeService::class.java)
        i.action = BridgeService.ACTION_START
        i.putExtra(BridgeService.EXTRA_ROLE, if (isSender) "sender" else "receiver")
        i.putExtra(BridgeService.EXTRA_TRANSPORT, if (isWifi) "wifi" else "bt")
        i.putExtra(BridgeService.EXTRA_IP, etIp.text.toString().trim())
        i.putExtra(BridgeService.EXTRA_PORT, port())
        i.putExtra(BridgeService.EXTRA_KEY, etKey.text.toString().trim())
        i.putExtra(BridgeService.EXTRA_BT_ADDR, selectedBtAddr() ?: "")
        tvStatus.text = "Запуск…"
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            tvStatus.text = "Не удалось запустить: ${e.message}"
        }
    }

    // ---------------------------------------------------------------------------------
    // Вспомогательные кнопки
    // ---------------------------------------------------------------------------------

    private fun grantMockViaRoot() {
        tvStatus.text = "Запрашиваю root… (подтверди запрос в менеджере root)"
        val pkg = packageName
        Thread {
            val set = Root.run(
                "appops set $pkg android:mock_location allow || " +
                    "cmd appops set $pkg android:mock_location allow"
            )
            val get = Root.run(
                "appops get $pkg android:mock_location || " +
                    "cmd appops get $pkg android:mock_location"
            )
            val msg = if (set.code < 0) {
                "Root недоступен: ${set.out}"
            } else {
                "Проверка прав: " + get.out.ifEmpty { "(пусто)" } +
                    "\nДолжно быть: MOCK_LOCATION: allow. Теперь можно запускать получателя."
            }
            runOnUiThread { tvStatus.text = msg }
        }.start()
    }

    private fun openDevSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun screenOffViaRoot() {
        Thread {
            val r = Root.run("input keyevent 223")
            if (r.code != 0) {
                runOnUiThread { tvStatus.text = "Не удалось погасить экран: ${r.out}" }
            }
        }.start()
    }

    private fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Оптимизация батареи уже отключена", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Открой настройки батареи вручную", Toast.LENGTH_LONG).show()
        }
    }
}
