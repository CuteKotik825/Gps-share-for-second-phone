package com.example.gpsbridge

import java.util.concurrent.TimeUnit

/** Запуск команд через su. Вызывать только из фонового потока. */
object Root {

    data class Result(val code: Int, val out: String)

    fun run(cmd: String, timeoutSec: Long = 30): Result {
        return try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Result(-1, "превышено время ожидания (подтвердили запрос root?)")
            } else {
                val out = p.inputStream.bufferedReader().use { it.readText() }
                Result(p.exitValue(), out.trim())
            }
        } catch (e: Exception) {
            Result(-2, e.message ?: e.toString())
        }
    }
}
