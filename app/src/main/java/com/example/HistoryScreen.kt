package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.launch
import java.io.File

enum class HistoryFilter {
    ALL, ACTIVE, COMPLETED, FAILED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    downloadRepository: DownloadRepository,
    onNavigateToTerminal: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Live collection of Room flow
    val downloads by downloadRepository.allDownloads.collectAsState(initial = emptyList())
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }

    // Dialog state
    var deleteTarget by remember { mutableStateOf<DownloadEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Filter lists
    val filteredDownloads = remember(downloads, selectedFilter) {
        when (selectedFilter) {
            HistoryFilter.ALL -> downloads
            HistoryFilter.ACTIVE -> downloads.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
            HistoryFilter.COMPLETED -> downloads.filter { it.status == DownloadStatus.COMPLETED }
            HistoryFilter.FAILED -> downloads.filter { it.status == DownloadStatus.FAILED }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "أرشيف وسجل التحميلات",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToTerminal) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("محاكي الطرفية والتشخيص") } },
                            state = rememberTooltipState()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = "محاكي الطرفية والتشخيص",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Filter Selector Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChipItem(
                    selected = selectedFilter == HistoryFilter.ALL,
                    label = "الكل",
                    count = downloads.size,
                    onClick = { selectedFilter = HistoryFilter.ALL },
                    modifier = Modifier.weight(1f)
                )

                FilterChipItem(
                    selected = selectedFilter == HistoryFilter.ACTIVE,
                    label = "جاري",
                    count = downloads.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING },
                    onClick = { selectedFilter = HistoryFilter.ACTIVE },
                    modifier = Modifier.weight(1f)
                )

                FilterChipItem(
                    selected = selectedFilter == HistoryFilter.COMPLETED,
                    label = "مكتمل",
                    count = downloads.count { it.status == DownloadStatus.COMPLETED },
                    onClick = { selectedFilter = HistoryFilter.COMPLETED },
                    modifier = Modifier.weight(1f)
                )

                FilterChipItem(
                    selected = selectedFilter == HistoryFilter.FAILED,
                    label = "فاشل",
                    count = downloads.count { it.status == DownloadStatus.FAILED },
                    onClick = { selectedFilter = HistoryFilter.FAILED },
                    modifier = Modifier.weight(1f)
                )
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))

            if (filteredDownloads.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = when (selectedFilter) {
                                HistoryFilter.ACTIVE -> Icons.Rounded.Download
                                HistoryFilter.COMPLETED -> Icons.Rounded.CheckCircleOutline
                                HistoryFilter.FAILED -> Icons.Rounded.ErrorOutline
                                else -> Icons.Rounded.Inbox
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "السجل فارغ في هذا التصنيف",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "سيظهر أي فيديو تقوم بمشاركته أو تحميله هنا في الوقت الفعلي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredDownloads, key = { it.id }) { download ->
                        HistoryCardItem(
                            download = download,
                            onRetry = {
                                coroutineScope.launch {
                                    val updated = download.copy(
                                        status = DownloadStatus.QUEUED,
                                        progressPercent = 0f,
                                        downloadedBytes = 0L,
                                        errorMessage = null
                                    )
                                    downloadRepository.update(updated)
                                    
                                    val serviceIntent = Intent(context, DownloadService::class.java).apply {
                                        action = DownloadService.ACTION_START_DOWNLOAD
                                        putExtra(DownloadService.EXTRA_DOWNLOAD_ID, download.id)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                    Toast.makeText(context, "تمت إعادة محاولة التحميل 🔄", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenFile = {
                                openFileInChooser(context, download.filePath)
                            },
                            onDelete = {
                                deleteTarget = download
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog && deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deleteTarget = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "حذف سجل التحميل",
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "هل تريد حذف الفيديو \"${target.title}\"؟ يمكنك اختيار حذف السجل فقط مع الاحتفاظ بالملف أو حذفهما معًا.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            // Delete record only
                            downloadRepository.deleteById(target.id)
                            showDeleteDialog = false
                            deleteTarget = null
                            Toast.makeText(context, "تم حذف السجل للملف بنجاح", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حذف السجل فقط")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            // Delete record & local storage file
                            try {
                                val file = File(target.filePath)
                                if (file.exists()) {
                                    file.delete()
                                }
                            } catch (e: Exception) {
                                // ignore delete errors
                            }
                            downloadRepository.deleteById(target.id)
                            showDeleteDialog = false
                            deleteTarget = null
                            Toast.makeText(context, "تم حذف السجل والملف الفيزيائي بالكامل", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("حذف السجل والملف معًا")
                }
            }
        )
    }
}

@Composable
fun FilterChipItem(
    selected: Boolean,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = count.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryCardItem(
    download: DownloadEntity,
    onRetry: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (download.status) {
        DownloadStatus.QUEUED -> MaterialTheme.colorScheme.outline
        DownloadStatus.RUNNING -> MaterialTheme.colorScheme.primary
        DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadStatus.PAUSED -> MaterialTheme.colorScheme.secondary
    }

    val statusLabel = when (download.status) {
        DownloadStatus.QUEUED -> "في الانتظار"
        DownloadStatus.RUNNING -> "جاري التحميل"
        DownloadStatus.COMPLETED -> "اكتمل بنجاح"
        DownloadStatus.FAILED -> "فشل التحميل"
        DownloadStatus.PAUSED -> "موؤقت"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier.size(width = 90.dp, height = 60.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    SubcomposeAsyncImage(
                        model = download.thumbnailUrl,
                        contentDescription = "صورة الفيديو",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    )
                }

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = "$statusLabel | ${formatHistoryFileSize(download.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = download.status == DownloadStatus.RUNNING) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    LinearProgressIndicator(
                        progress = { download.progressPercent / 100f },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${download.progressPercent.toInt()}% كحد أقصى للتحميل",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "${formatHistoryFileSize(download.downloadedBytes)} / ${formatHistoryFileSize(download.totalBytes)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            AnimatedVisibility(visible = download.status == DownloadStatus.FAILED && !download.errorMessage.isNullOrEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                ) {
                    Text(
                        text = download.errorMessage ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(10.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.05f))
            Spacer(modifier = Modifier.height(8.dp))

            // Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "حذف من السجل",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                when (download.status) {
                    DownloadStatus.FAILED -> {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("إعادة المحاولة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        Button(
                            onClick = onOpenFile,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("فتح الملف تشغيل", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    else -> {
                        // Downloading/Queued: simple indicator tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (download.status == DownloadStatus.RUNNING) "جاري التحميل..." else "قيد الانتظار...",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Robust Byte converter matching requirements to human-readable memory counts.
 */
private fun formatHistoryFileSize(bytes: Long): String {
    if (bytes <= 0) return "حجم تلقائي"
    val kcs = bytes / 1024.0
    val mcs = kcs / 1024.0
    val gcs = mcs / 1024.0
    return when {
        gcs >= 1.0 -> String.format("%.2f GB", gcs)
        mcs >= 1.0 -> String.format("%.1f MB", mcs)
        kcs >= 1.0 -> String.format("%.1f KB", kcs)
        else -> "$bytes B"
    }
}

/**
 * Uses robust FileProvider to launch Intent to open completed video/audio file with external applications safely.
 */
private fun openFileInChooser(context: Context, filePath: String) {
    try {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "لم يتم العثور على الملف المحلي، قد يكون تم نقله أو حذفه.", Toast.LENGTH_SHORT).show()
            return
        }

        // Get extension to find MIME
        val ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(file).toString())
        var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (mimeType == null) {
            // fallback logic
            mimeType = if (filePath.endsWith(".mp4", ignoreCase = true) || filePath.endsWith(".mkv", ignoreCase = true)) {
                "video/*"
            } else if (filePath.endsWith(".mp3", ignoreCase = true) || filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".aac", ignoreCase = true)) {
                "audio/*"
            } else {
                "*/*"
            }
        }

        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(Intent.createChooser(intent, "افتح الملف باستخدام:"))
    } catch (e: Exception) {
        Toast.makeText(context, "لا يتوفر تطبيق متوافق لفتح هذا النوع من الملفات: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
