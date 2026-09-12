package com.displayio.viewmodel

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.displayio.model.SequenceData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import androidx.compose.runtime.saveable.rememberSaveable

data class SequenceInfo(val dir: File, val name: String, val addDate: Long)

class DisplayViewModel(application: Application) : AndroidViewModel(application) {

    private var socketReadJob: Job? = null

    var importedSequences by mutableStateOf<List<SequenceInfo>>(emptyList())
    var currentSequence by mutableStateOf<SequenceData?>(null)
    var currentDir by mutableStateOf<File?>(null)

    var currentLanguage by mutableStateOf("中文")

    var currentDisplayText by mutableStateOf("等待信号...")
    private var currentActiveEnDes: String? = null
    private var isCurrentlyStopped by mutableStateOf(false)

    var usbConnectionState by mutableStateOf("USB 未连接")
    private var usbSerialPort: UsbSerialPort? = null
    private var readJob: Job? = null

    // 💡 双独立音轨：主控端的播放冲突逻辑在这里完美还原
    // Voice音轨：用于主动作、全局语音。触发新的会立刻打断旧的。
    private var voicePlayer: MediaPlayer? = null
    // BGM音轨：用于附属音频（如太极、拳击音乐）。独立启停，与Voice轨互不干扰。
    private var bgmPlayer: MediaPlayer? = null

    init {
        loadImportedSequences()
        startSocketReadLoop()
    }

    fun importZipFile(context: Context, uri: Uri) {
        val destDir = File(context.filesDir, "seq_${System.currentTimeMillis()}")
        destDir.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).use { fos -> zis.copyTo(fos) }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            loadImportedSequences()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadImportedSequences() {
        val rootDir = getApplication<Application>().filesDir
        val dirs = rootDir.listFiles { file -> file.isDirectory && file.name.startsWith("seq_") }?.toList() ?: emptyList()

        importedSequences = dirs.map { dir ->
            val timestamp = dir.name.substringAfter("seq_").toLongOrNull() ?: 0L
            var seqName = "Unknown Config"
            try {
                val jsonFile = File(dir, "config.json")
                if (jsonFile.exists()) {
                    val jsonStr = jsonFile.readText()
                    val data = Gson().fromJson(jsonStr, SequenceData::class.java)
                    seqName = data.sequenceName
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            SequenceInfo(dir, seqName, timestamp)
        }.sortedByDescending { it.addDate }
    }

    private fun startSocketReadLoop() {
        socketReadJob?.cancel()
        socketReadJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    // 连接 Ubuntu 宿主机的 socat 桥
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("10.0.3.2", 8080), 2000)
                    val inputStream = socket.getInputStream()
                    val buffer = ByteArray(4096)
                    var readStr = ""

                    withContext(Dispatchers.Main) {
                        usbConnectionState = "已桥接"
                    }

                    // 持续读取 ESP32 传回的数据
                    while (isActive && socket.isConnected) {
                        val len = inputStream.read(buffer)
                        if (len > 0) {
                            readStr += String(buffer, 0, len, Charsets.UTF_8)
                            while (readStr.contains("\n")) {
                                val newlineIdx = readStr.indexOf("\n")
                                val line = readStr.substring(0, newlineIdx).trim()
                                readStr = readStr.substring(newlineIdx + 1)
                                if (line.isNotEmpty()) {
                                    // 💡 直接复用原有的解析逻辑！
                                    processSerialLine(line)
                                }
                            }
                        } else if (len == -1) {
                            break // 连接断开
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    delay(3000)
                }
            }
        }
    }

    fun deleteSequence(info: SequenceInfo) {
        info.dir.deleteRecursively()
        loadImportedSequences()
    }

    fun openSequence(dir: File) {
        try {
            val jsonFile = File(dir, "config.json")
            if (jsonFile.exists()) {
                val jsonStr = jsonFile.readText()
                val data = Gson().fromJson(jsonStr, SequenceData::class.java)
                currentSequence = data
                currentDir = dir
                currentLanguage = data.languages?.firstOrNull() ?: "中文"
                currentDisplayText = "等待信号..."
                currentActiveEnDes = null
                isCurrentlyStopped = false
                stopAllAudios()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun closeSequence() {
        stopAllAudios()
        currentSequence = null
        currentDir = null
        currentDisplayText = "等待信号..."
        currentActiveEnDes = null
    }

    private fun stopAllAudios() {
        voicePlayer = safeStopPlayer(voicePlayer)
        bgmPlayer = safeStopPlayer(bgmPlayer)
    }

    private fun safeStopPlayer(player: MediaPlayer?): MediaPlayer? {
        try {
            if (player?.isPlaying == true) {
                player.stop()
            }
            player?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun connectToEsp32(context: Context) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        var availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (availableDrivers.isEmpty()) {
            val customTable = ProbeTable()
            customTable.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
            val customProber = UsbSerialProber(customTable)
            availableDrivers = customProber.findAllDrivers(manager)
        }

        if (availableDrivers.isEmpty()) {
            usbConnectionState = "未找到设备"
            return
        }

        val driver = availableDrivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            usbConnectionState = "请授权 USB"
            val intent = Intent("com.displayio.USB_PERMISSION").apply { setPackage(context.packageName) }
            val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.requestPermission(device, permissionIntent)
            return
        }

        try {
            val connection = manager.openDevice(device)
            if (connection == null) {
                usbConnectionState = "连接拒绝"
                return
            }

            usbSerialPort = driver.ports[0]
            usbSerialPort?.open(connection)
            usbSerialPort?.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort?.dtr = true
            usbSerialPort?.rts = true

            usbConnectionState = "已连接接收端"
            startReadLoop()

        } catch (e: Exception) {
            usbConnectionState = "错误: ${e.message}"
            usbSerialPort?.close()
            usbSerialPort = null
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            var readStr = ""
            while (isActive && usbSerialPort?.isOpen == true) {
                try {
                    val len = usbSerialPort?.read(buffer, 100) ?: 0
                    if (len > 0) {
                        readStr += String(buffer, 0, len)
                        while (readStr.contains("\n")) {
                            val newlineIdx = readStr.indexOf("\n")
                            val line = readStr.substring(0, newlineIdx).trim()
                            readStr = readStr.substring(newlineIdx + 1)
                            if (line.isNotEmpty()) {
                                processSerialLine(line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { usbConnectionState = "连接中断" }
                    break
                }
            }
        }
    }

    private fun processSerialLine(line: String) {
        Log.d("ESP32_RECV", line)
        try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map: Map<String, String> = Gson().fromJson(line, type) ?: return

            if (map["event"] == "boot") {
                val mac = map["mac"] ?: return
                viewModelScope.launch(Dispatchers.Main) {
                    currentDisplayText = "设备已就绪\nMAC: $mac"
                }
            } else if (map["event"] == "recv_data") {
                val payload = map["payload"] ?: return
                viewModelScope.launch(Dispatchers.Main) {
                    handlePayload(payload)
                }
            }
        } catch (e: Exception) {
            // 忽略非 JSON 日志
        }
    }

    // 💡 处理解析指令（附带语言后缀）
    private fun handlePayload(payload: String) {
        val cmd = payload.trim()
        val isStop = cmd.startsWith("Stop")
        var targetKey = cmd.removePrefix("Start").removePrefix("Stop").trim()

        val seq = currentSequence

        // 解析主控端传来的语言后缀（例如 "1-b#1" 代表英语）
        if (targetKey.contains("#")) {
            val parts = targetKey.split("#")
            targetKey = parts[0].trim()
            val langIdx = parts[1].trim().toIntOrNull()

            if (langIdx != null && seq?.languages != null && langIdx >= 0 && langIdx < seq.languages.size) {
                currentLanguage = seq.languages[langIdx]
            }
        }

        updateDisplayAndAudio(targetKey, isStop)
    }

    // 💡 音频路由与播放控制核心逻辑
    private fun updateDisplayAndAudio(targetKey: String, isStop: Boolean) {
        val seq = currentSequence
        currentActiveEnDes = targetKey
        isCurrentlyStopped = isStop

        if (seq == null || currentDir == null) {
            currentDisplayText = if (isStop) "（停止）$targetKey" else targetKey
            return
        }

        val lang = currentLanguage
        var foundName: String? = null
        var audioCategory = 1 // 1: Voice轨(主动作/全局), 2: BGM轨(附属音频)
        var audioFileToPlay: String? = null

        // 1. 扫描动作库
        for (step in seq.steps) {
            for (action in step.actions) {
                // 1.1 检查附属 BGM (走 BGM 独立音轨)
                val matchSub = (action.subAudioEnDes?.values?.contains(targetKey) == true) ||
                        (action.subAudioName?.values?.contains(targetKey) == true) ||
                        (action.subAudioFileName?.values?.contains(targetKey) == true) ||
                        (action.subAudioName?.get("英语") == targetKey) ||
                        (action.subAudioName?.get("中文") == targetKey)

                if (matchSub && action.subAudioFileName != null) {
                    foundName = action.subAudioName?.get(lang) ?: action.name
                    audioFileToPlay = action.subAudioFileName?.get(lang)
                    audioCategory = 2 // 分配给 BGM 轨
                    break
                }

                // 1.2 检查主动作 (走 Voice 音轨)
                val matchMain = (action.en_des == targetKey) ||
                        (action.name == targetKey) ||
                        (action.id == targetKey) ||
                        (action.audioFileNames.values.any { list -> list.contains(targetKey) })

                if (matchMain) {
                    foundName = action.name
                    audioFileToPlay = action.audioFileNames[lang]?.randomOrNull()
                    audioCategory = 1 // 分配给 Voice 轨
                    break
                }
            }
            if (foundName != null) break
        }

        // 2. 扫描全局语音 (同样走 Voice 音轨，会打断主动作)
        if (foundName == null) {
            seq.otherBroadcasts?.forEach { bc ->
                val matchGlobal = (bc.en_des?.values?.contains(targetKey) == true) ||
                        (bc.name == targetKey) ||
                        (bc.audioFileName.values.contains(targetKey))
                if (matchGlobal) {
                    foundName = bc.name
                    audioFileToPlay = bc.audioFileName[lang]
                    audioCategory = 1 // 分配给 Voice 轨
                    return@forEach
                }
            }
        }

        val displayName = foundName ?: targetKey

        // 💡 精确启停控制：只会操作命中的对应音轨，互不干扰
        if (isStop) {
            currentDisplayText = "（停止）$displayName"
            if (audioCategory == 1) {
                voicePlayer = safeStopPlayer(voicePlayer)
            } else {
                bgmPlayer = safeStopPlayer(bgmPlayer)
            }
        } else {
            currentDisplayText = displayName
            val audioFile = if (audioFileToPlay != null) File(File(currentDir, "audio"), audioFileToPlay) else null

            if (audioCategory == 1) {
                voicePlayer = safeStopPlayer(voicePlayer) // 先停止旧的声音
                voicePlayer = createAndStartPlayer(audioFile) // 播放新的声音
            } else {
                bgmPlayer = safeStopPlayer(bgmPlayer) // 先停止旧的 BGM
                bgmPlayer = createAndStartPlayer(audioFile) // 播放新的 BGM
            }
        }
    }

    // 屏幕上方按钮手动切换语言时的 UI 无声刷新（兼容旧操作方式）
    fun refreshDisplayForLanguage() {
        val targetKey = currentActiveEnDes ?: return
        val seq = currentSequence ?: return
        val lang = currentLanguage
        var foundName: String? = null

        for (step in seq.steps) {
            for (action in step.actions) {
                val matchSub = (action.subAudioName?.values?.contains(targetKey) == true) ||
                        (action.subAudioFileName?.values?.contains(targetKey) == true) ||
                        (action.subAudioName?.get("英语") == targetKey) ||
                        (action.subAudioName?.get("中文") == targetKey)

                if (matchSub) {
                    foundName = action.subAudioName?.get(lang) ?: action.name
                    break
                }

                val matchMain = (action.id == targetKey) ||
                        (action.audioFileNames.values.any { list -> list.contains(targetKey) })

                if (matchMain) {
                    foundName = action.name
                    break
                }
            }
            if (foundName != null) break
        }

        if (foundName == null) {
            seq.otherBroadcasts?.forEach { bc ->
                val matchGlobal = (bc.audioFileName.values.contains(targetKey)) ||
                        (bc.audioFileName[lang] == targetKey)
                if (matchGlobal) {
                    foundName = bc.name
                    return@forEach
                }
            }
        }

        val displayName = foundName ?: targetKey
        currentDisplayText = if (isCurrentlyStopped) "（停止）$displayName" else displayName
    }

    private fun createAndStartPlayer(file: File?): MediaPlayer? {
        if (file != null && file.exists()) {
            return try {
                MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}