package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val level: String, // "DEBUG", "INFO", "WARNING", "ERROR"
    val timestamp: String,
    val message: String
)

object TerminalLogManager {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun log(level: String, message: String) {
        val entry = LogEntry(
            level = level,
            timestamp = timeFormat.format(Date()),
            message = message
        )
        synchronized(this) {
            val currentList = _logs.value.toMutableList()
            currentList.add(entry)
            if (currentList.size > 500) {
                currentList.removeAt(0)
            }
            _logs.value = currentList
        }
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }
}
