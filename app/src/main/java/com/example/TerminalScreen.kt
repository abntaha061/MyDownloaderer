package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val logs by TerminalLogManager.logs.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to latest log entries when standard layout loads more
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "محاكي الطرفية والتشخيص (Logs)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { TerminalLogManager.clear() }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = "مسح السجل",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFF1E1E1E)) // Elegant terminal dark canvas
        ) {
            // Header Bar with Quick Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val fullLogText = logs.joinToString("\n") { "[${it.level}] [${it.timestamp}]: ${it.message}" }
                        if (fullLogText.isNotEmpty()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("ytdl_logs", fullLogText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "تم نسخ السجل بالكامل إلى الحافظة! ✅", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "السجل فارغ حاليًا", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نسخ السجل كاملًا", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val fullLogText = logs.joinToString("\n") { "[${it.level}] [${it.timestamp}]: ${it.message}" }
                        if (fullLogText.isNotEmpty()) {
                            shareLogsAsFile(context, fullLogText)
                        } else {
                            Toast.makeText(context, "السجل فارغ حاليًا", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("مشاركة ملف السجل", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Realtime console terminal list
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = Color.Gray.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "لا توجد سجلات حالية. ابدأ تحميلًا لتفحص التقدم.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs) { entry ->
                        TerminalLineItem(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
fun TerminalLineItem(entry: LogEntry) {
    val textColor = when {
        entry.level == "ERROR" || entry.message.contains("ERROR:", ignoreCase = true) -> Color(0xFFFF5252) // Vivid Red
        entry.level == "WARNING" || entry.message.contains("WARNING:", ignoreCase = true) -> Color(0xFFFFD740) // Amber Yellow
        entry.level == "INFO" -> Color(0xFF69F0AE) // Bright Mint Green
        else -> Color(0xFFE0E0E0) // Light Grey
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.15f))
            .padding(vertical = 4.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Log stamp tag
        Text(
            text = "[${entry.timestamp}]",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color.Gray,
            modifier = Modifier.padding(end = 8.dp)
        )

        // Level indicator
        Text(
            text = "[${entry.level}]",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = 8.dp)
        )

        // Output detail body text
        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = textColor,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Creates temporary log text file in device cache directory & shares URI using robust FileProvider.
 */
private fun shareLogsAsFile(context: Context, fullLogs: String) {
    try {
        val cacheFolder = File(context.cacheDir, "logs")
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }

        val logFile = File(cacheFolder, "pure_download_diagnostic_logs.txt")
        val writer = FileWriter(logFile)
        writer.write(fullLogs)
        writer.close()

        val authority = "${context.packageName}.fileprovider"
        val fileUri = FileProvider.getUriForFile(context, authority, logFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_SUBJECT, "سجل تشخيص PureDownload")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "شارك ملف السجل عبر:"))
    } catch (e: Exception) {
        Toast.makeText(context, "فشل إنشاء الملف لمشاركته: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
