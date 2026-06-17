package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.example.ui.theme.MyApplicationTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ShareSheetState {
    object Loading : ShareSheetState
    data class Error(val message: String) : ShareSheetState
    data class Success(val url: String, val info: VideoInfo) : ShareSheetState
}

@Suppress("DEPRECATION")
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {

    @Inject
    lateinit var ytdlpEngine: YtdlpEngine

    @Inject
    lateinit var downloadRepository: DownloadRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract shared link when ACTION_SEND intent is delivered
        val text = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }

        val url = text?.let { extractUrl(it) }

        if (url == null) {
            Toast.makeText(this, "لم يتم العثور على رابط صالح", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            MyApplicationTheme {
                val coroutineScope = rememberCoroutineScope()
                var state by remember { mutableStateOf<ShareSheetState>(ShareSheetState.Loading) }

                LaunchedEffect(url) {
                    coroutineScope.launch {
                        Log.d("PureDownload", "البدء في جلب معلومات الرابط: $url")
                        val result = ytdlpEngine.extractInfo(url)
                        result.onSuccess { info ->
                            Log.d("PureDownload", "نجح جلب معلومات الفيديو!")
                            Log.d("PureDownload", "عنوان الفيديو: ${info.title}")
                            Log.d("PureDownload", "المدة الإجمالية: ${info.duration} ثانية")
                            Log.d("PureDownload", "عدد الجودات المتاحة: ${info.formats.size}")
                            state = ShareSheetState.Success(url, info)
                        }.onFailure { exception ->
                            val errMsg = exception.message ?: "حدث خطأ غير معروف"
                            Log.e("PureDownload", "فشل جلب معلومات الفيديو: $errMsg", exception)
                            state = ShareSheetState.Error(errMsg)
                        }
                    }
                }

                ShareSheetContainer(
                    state = state,
                    onDismiss = { finish() },
                    onStartDownload = { formatId, ext, subtitleLang, sponsorblockAction, sponsorblockCategories, scheduledAt ->
                        val successState = state as? ShareSheetState.Success
                        if (successState != null) {
                            triggerDownload(
                                url = url,
                                formatId = formatId,
                                ext = ext,
                                subtitleLang = subtitleLang,
                                sponsorblockAction = sponsorblockAction,
                                sponsorblockCategories = sponsorblockCategories,
                                videoInfo = successState.info,
                                scheduledAt = scheduledAt
                            )
                        }
                    },
                    onStartPlaylistDownloads = { items ->
                        triggerPlaylistDownloads(items)
                    }
                )
            }
        }
    }

    /**
     * Extracts absolute url patterns using regular expressions (matches http/https links).
     */
    private fun extractUrl(text: String): String? {
        val regex = "https?://[\\w\\d:#@%/;\$()~_?\\+-=\\\\\\.&]+".toRegex()
        return regex.find(text)?.value
    }

    /**
     * Trigger video download with advanced options.
     */
    private fun triggerDownload(
        url: String,
        formatId: String,
        ext: String,
        subtitleLang: String?,
        sponsorblockAction: String,
        sponsorblockCategories: Set<String>,
        videoInfo: VideoInfo,
        scheduledAt: Long?
    ) {
        lifecycleScope.launch {
            try {
                // Sanitize file name for file system safety
                val cleanTitle = videoInfo.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                val downloadsDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val outputFile = java.io.File(downloadsDir, "$cleanTitle.$ext")

                // Convert categories to comma-separated string
                val categoriesStr = sponsorblockCategories.joinToString(",")

                val isScheduled = scheduledAt != null
                val entity = DownloadEntity(
                    url = url,
                    title = videoInfo.title,
                    thumbnailUrl = videoInfo.thumbnailUrl,
                    filePath = outputFile.absolutePath,
                    formatId = formatId,
                    status = if (isScheduled) DownloadStatus.SCHEDULED else DownloadStatus.QUEUED,
                    progressPercent = 0f,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                    errorMessage = null,
                    createdAt = System.currentTimeMillis(),
                    subtitleLang = subtitleLang,
                    sponsorblockAction = sponsorblockAction,
                    sponsorblockCategories = categoriesStr,
                    scheduledAt = scheduledAt
                )

                // Save record in the persistent DB
                val downloadId = downloadRepository.insert(entity)

                if (isScheduled) {
                    DownloadSchedulerHelper.scheduleAlarm(this@ShareReceiverActivity, downloadId, scheduledAt!!)
                    Toast.makeText(this@ShareReceiverActivity, "تمت جدولة التحميل بنجاح في الوقت المحدد 📅", Toast.LENGTH_SHORT).show()
                } else {
                    // Fire Foreground service
                    val serviceIntent = Intent(this@ShareReceiverActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_START_DOWNLOAD
                        putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    Toast.makeText(this@ShareReceiverActivity, "تمت إضافة الفيديو إلى قائمة الانتظار للتحميل ⚡", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("PureDownload", "فشل بدء التحميل: ${e.message}", e)
                Toast.makeText(this@ShareReceiverActivity, "حدث خطأ غير متوقع أثناء تسجيل التحميل", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun triggerPlaylistDownloads(
        items: List<Triple<PlaylistEntry, String, Boolean>>
    ) {
        lifecycleScope.launch {
            try {
                val downloadsDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                var firstId: Long? = null

                items.forEach { (entry, ext, convertToMp3) ->
                    val cleanTitle = entry.title.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
                    val outputFile = java.io.File(downloadsDir, "$cleanTitle.$ext")

                    val entity = DownloadEntity(
                        url = entry.url,
                        title = entry.title,
                        thumbnailUrl = entry.thumbnailUrl,
                        filePath = outputFile.absolutePath,
                        formatId = if (convertToMp3) "bestaudio" else "best",
                        status = DownloadStatus.QUEUED,
                        progressPercent = 0f,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        errorMessage = null,
                        createdAt = System.currentTimeMillis(),
                        convertToMp3 = convertToMp3
                    )

                    val downloadId = downloadRepository.insert(entity)
                    if (firstId == null) {
                        firstId = downloadId
                    }
                }

                firstId?.let { downloadId ->
                    val serviceIntent = Intent(this@ShareReceiverActivity, DownloadService::class.java).apply {
                        action = DownloadService.ACTION_START_DOWNLOAD
                        putExtra(DownloadService.EXTRA_DOWNLOAD_ID, downloadId)
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }

                Toast.makeText(this@ShareReceiverActivity, "تمت إضافة ${items.size} ملفات إلى قائمة التحميل بنجاح ⚡", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("PureDownload", "فشل بدء تحميل قائمة التشغيل: ${e.message}", e)
                Toast.makeText(this@ShareReceiverActivity, "حدث خطأ أثناء تحميل قائمة التشغيل", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheetContainer(
    state: ShareSheetState,
    onDismiss: () -> Unit,
    onStartDownload: (formatId: String, ext: String, subtitleLang: String?, sponsorblockAction: String, sponsorblockCategories: Set<String>, scheduledAt: Long?) -> Unit,
    onStartPlaylistDownloads: (items: List<Triple<PlaylistEntry, String, Boolean>>) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = { BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                is ShareSheetState.Loading -> {
                    LoadingSkeletonLayout()
                }

                is ShareSheetState.Error -> {
                    ErrorContentLayout(
                        errorMessage = state.message,
                        onDismiss = onDismiss
                    )
                }

                is ShareSheetState.Success -> {
                    if (state.info.isPlaylist) {
                        PlaylistDetailsContentLayout(
                            playlistTitle = state.info.title,
                            entries = state.info.playlistEntries,
                            onDismiss = onDismiss,
                            onStartPlaylistDownloads = { items ->
                                onStartPlaylistDownloads(items)
                                onDismiss()
                            }
                        )
                    } else {
                        VideoDetailsContentLayout(
                            url = state.url,
                            videoInfo = state.info,
                            onStartDownload = { formatId, ext, sub, sbAction, sbCats, scheduledAt ->
                                onStartDownload(formatId, ext, sub, sbAction, sbCats, scheduledAt)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Beautiful skeleton shimmer / pulsing loader ensuring perfect visual consistency during extraction.
 */
@Composable
fun LoadingSkeletonLayout() {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_fade"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 40.dp, top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )

        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )

        Spacer(modifier = Modifier.height(30.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "تحليل وتجهيز الفيديو...",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "نستخرج الصيغ والجودات المتاحة للتحميل عبر yt-dlp",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ErrorContentLayout(
    errorMessage: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 44.dp, top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "خطأ",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "فشل استخراج معلومات الفيديو",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
        ) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("إغلاق النافذة", fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun CustomPillSelector(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp
            ),
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Custom-branded layout displaying rich title information, collapsible groups of video formats, and action triggers.
 */
@Composable
fun VideoDetailsContentLayout(
    url: String,
    videoInfo: VideoInfo,
    onStartDownload: (formatId: String, ext: String, subtitleLang: String?, sponsorblockAction: String, sponsorblockCategories: Set<String>, scheduledAt: Long?) -> Unit
) {
    var selectedFormat by remember { mutableStateOf<FormatInfo?>(null) }
    var expandedMergedVideo by remember { mutableStateOf(true) } 
    var expandedAudioOnly by remember { mutableStateOf(false) }
    var expandedVideoOnly by remember { mutableStateOf(false) }

    // Advanced Options fields
    var selectedSubtitleLang by remember { mutableStateOf<String?>(null) }
    var sponsorblockAction by remember { mutableStateOf("none") } // none, mark, remove
    var selectedSponsorblockCategories by remember { mutableStateOf(setOf<String>()) }
    var isScheduled by remember { mutableStateOf(false) }
    var scheduledAtMs by remember { mutableStateOf<Long?>(null) }

    val allFormats = videoInfo.formats

    val audioFormats = allFormats.filter {
        it.vcodec.contains("none", ignoreCase = true) || it.vcodec.isEmpty()
    }

    val videoOnlyFormats = allFormats.filter {
        !it.vcodec.contains("none", ignoreCase = true) && it.vcodec.isNotEmpty() &&
                (it.acodec.contains("none", ignoreCase = true) || it.acodec.isEmpty())
    }

    val mergedFormats = allFormats.filter {
        !it.vcodec.contains("none", ignoreCase = true) && it.vcodec.isNotEmpty()
    }

    val animatedSelectedFormat = remember(selectedFormat) { selectedFormat }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .heightIn(max = 580.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    modifier = Modifier
                        .size(width = 120.dp, height = 75.dp),
                    shape = RoundedCornerShape(10.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    SubcomposeAsyncImage(
                        model = videoInfo.thumbnailUrl,
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

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = videoInfo.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = "مدة الفيديو",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = formatDuration(videoInfo.duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Section 1: "فيديو وصوت (جودة فائقة)"
        item {
            CategoryHeader(
                title = "فيديو وصوت (جودة فائقة)",
                icon = Icons.Rounded.Movie,
                itemCount = mergedFormats.size,
                isExpanded = expandedMergedVideo,
                onToggle = { expandedMergedVideo = !expandedMergedVideo }
            )
        }

        if (expandedMergedVideo) {
            if (mergedFormats.isEmpty()) {
                item { EmptyCategoryLabel() }
            } else {
                items(mergedFormats) { format ->
                    FormatItemRow(
                        format = format,
                        isSelected = selectedFormat?.formatId == format.formatId,
                        isMergedFormat = true,
                        onSelect = { selectedFormat = format }
                    )
                }
            }
        }

        // Section 2: "صوت فقط"
        item {
            Spacer(modifier = Modifier.height(8.dp))
            CategoryHeader(
                title = "صوت فقط (موسيقى ومحاضرات)",
                icon = Icons.Rounded.Audiotrack,
                itemCount = audioFormats.size,
                isExpanded = expandedAudioOnly,
                onToggle = { expandedAudioOnly = !expandedAudioOnly }
            )
        }

        if (expandedAudioOnly) {
            if (audioFormats.isEmpty()) {
                item { EmptyCategoryLabel() }
            } else {
                items(audioFormats) { format ->
                    FormatItemRow(
                        format = format,
                        isSelected = selectedFormat?.formatId == format.formatId,
                        isMergedFormat = false,
                        onSelect = { selectedFormat = format }
                    )
                }
            }
        }

        // Section 3: "فيديو فقط (بدون صوت)"
        item {
            Spacer(modifier = Modifier.height(8.dp))
            CategoryHeader(
                title = "فيديو صامت (معرض صور أو تجميع)",
                icon = Icons.Rounded.VideoLibrary,
                itemCount = videoOnlyFormats.size,
                isExpanded = expandedVideoOnly,
                onToggle = { expandedVideoOnly = !expandedVideoOnly }
            )
        }

        if (expandedVideoOnly) {
            if (videoOnlyFormats.isEmpty()) {
                item { EmptyCategoryLabel() }
            } else {
                items(videoOnlyFormats) { format ->
                    FormatItemRow(
                        format = format,
                        isSelected = selectedFormat?.formatId == format.formatId,
                        isMergedFormat = false,
                        onSelect = { selectedFormat = format }
                    )
                }
            }
        }

        // Section: "Advanced Options" (خيارات متقدمة)
        item {
            Spacer(modifier = Modifier.height(16.dp))
            var expandedAdvanced by remember { mutableStateOf(false) }
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedAdvanced = !expandedAdvanced },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "خيارات متقدمة (ترجمة وSponsorBlock)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Icon(
                        imageVector = if (expandedAdvanced) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (expandedAdvanced) "تقليص" else "توسيع",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expandedAdvanced) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, start = 8.dp, end = 8.dp)
                ) {
                    // Subtitle Section
                    Text(
                        text = "ترجمة الفيديو المصاحبة:",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    if (videoInfo.subtitles.isEmpty()) {
                        Text(
                            text = "لا تتوفر ملفات ترجمة مدمجة أو تلقائية لهذا الفيديو.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CustomPillSelector(
                                selected = selectedSubtitleLang == null,
                                label = "بدون ترجمة",
                                onClick = { selectedSubtitleLang = null }
                            )
                            videoInfo.subtitles.forEach { sub ->
                                CustomPillSelector(
                                    selected = selectedSubtitleLang == sub.code,
                                    label = "${sub.name} (${if (sub.type == "manual") "يدوية" else "آلية"})",
                                    onClick = { selectedSubtitleLang = sub.code }
                                )
                            }
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), modifier = Modifier.padding(bottom = 16.dp))

                    // SponsorBlock Section
                    Text(
                        text = "تخطي إعلانات ومقاطع SponsorBlock:",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val options = listOf(
                            "none" to "تعطيل الميزة",
                            "mark" to "تعليم المقاطع",
                            "remove" to "حذف وقص"
                        )
                        options.forEach { (action, label) ->
                            CustomPillSelector(
                                selected = sponsorblockAction == action,
                                label = label,
                                onClick = { sponsorblockAction = action },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (sponsorblockAction != "none") {
                        Text(
                            text = "الفئات المراد معالجتها:",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        val categories = listOf(
                            "sponsor" to "إعلان ترويجي أو ممول (Sponsor)",
                            "intro" to "مقدمة وشارة البداية (Intro)",
                            "outro" to "خاتمة وشكر النهاية (Outro)",
                            "selfpromo" to "عروض ترويجية ذاتية (Self-promo)",
                            "interaction" to "طلب التفاعل: إعجاب ومتابعة (Interaction)",
                            "music_offtopic" to "موسيقى أو محتوى حشو خارجي (Music off-topic)"
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            categories.forEach { (cat, label) ->
                                val isChecked = selectedSponsorblockCategories.contains(cat)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedSponsorblockCategories = if (isChecked) {
                                                selectedSponsorblockCategories - cat
                                            } else {
                                                selectedSponsorblockCategories + cat
                                            }
                                        }
                                        .padding(vertical = 4.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedSponsorblockCategories = if (checked == true) {
                                                selectedSponsorblockCategories + cat
                                            } else {
                                                selectedSponsorblockCategories - cat
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        if (sponsorblockAction == "remove") {
                            Text(
                                text = "⚠️ تنبيه: ميزة الحذف تتطلب توفر ffmpeg بشكل سليم، وإلا قد لا تعمل المقاطع بشكل متناسق.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), modifier = Modifier.padding(bottom = 16.dp))

                    // Scheduling Section
                    Text(
                        text = "جدولة التحميل بوقت لاحق:",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val context = LocalContext.current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "تفعيل المنبه/الجدولة لتمكين التحميل لاحقاً",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isScheduled,
                            onCheckedChange = { checked ->
                                isScheduled = checked
                                if (!checked) {
                                    scheduledAtMs = null
                                } else {
                                    // auto-trigger date selection when turning on
                                    val calendar = java.util.Calendar.getInstance()
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, year, month, day ->
                                            android.app.TimePickerDialog(
                                                context,
                                                { _, hour, min ->
                                                    val selectedCal = java.util.Calendar.getInstance().apply {
                                                        set(java.util.Calendar.YEAR, year)
                                                        set(java.util.Calendar.MONTH, month)
                                                        set(java.util.Calendar.DAY_OF_MONTH, day)
                                                        set(java.util.Calendar.HOUR_OF_DAY, hour)
                                                        set(java.util.Calendar.MINUTE, min)
                                                        set(java.util.Calendar.SECOND, 0)
                                                        set(java.util.Calendar.MILLISECOND, 0)
                                                    }
                                                    if (selectedCal.timeInMillis > System.currentTimeMillis()) {
                                                        scheduledAtMs = selectedCal.timeInMillis
                                                    } else {
                                                        isScheduled = false
                                                        Toast.makeText(context, "الرجاء اختيار وقت مستقبلي!", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                                                calendar.get(java.util.Calendar.MINUTE),
                                                false
                                            ).show()
                                        },
                                        calendar.get(java.util.Calendar.YEAR),
                                        calendar.get(java.util.Calendar.MONTH),
                                        calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                            }
                        )
                    }

                    if (isScheduled && scheduledAtMs != null) {
                        val formattedDate = remember(scheduledAtMs) {
                            val sdf = java.text.SimpleDateFormat("yyyy/MM/dd hh:mm a", java.util.Locale.getDefault())
                            sdf.format(java.util.Date(scheduledAtMs!!))
                        }
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clickable {
                                    val calendar = java.util.Calendar.getInstance()
                                    android.app.DatePickerDialog(
                                        context,
                                        { _, year, month, day ->
                                            android.app.TimePickerDialog(
                                                context,
                                                { _, hour, min ->
                                                    val selectedCal = java.util.Calendar.getInstance().apply {
                                                        set(java.util.Calendar.YEAR, year)
                                                        set(java.util.Calendar.MONTH, month)
                                                        set(java.util.Calendar.DAY_OF_MONTH, day)
                                                        set(java.util.Calendar.HOUR_OF_DAY, hour)
                                                        set(java.util.Calendar.MINUTE, min)
                                                        set(java.util.Calendar.SECOND, 0)
                                                        set(java.util.Calendar.MILLISECOND, 0)
                                                    }
                                                    if (selectedCal.timeInMillis > System.currentTimeMillis()) {
                                                        scheduledAtMs = selectedCal.timeInMillis
                                                    } else {
                                                        Toast.makeText(context, "الرجاء اختيار وقت مستقبلي!", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                                                calendar.get(java.util.Calendar.MINUTE),
                                                false
                                            ).show()
                                        },
                                        calendar.get(java.util.Calendar.YEAR),
                                        calendar.get(java.util.Calendar.MONTH),
                                        calendar.get(java.util.Calendar.DAY_OF_MONTH)
                                    ).show()
                                },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    text = "الوقت المجدول: $formattedDate",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Alert Disclaimer Card
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "تنبيه هام",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "آليات تجاوز قيود يوتيوب وتحديد الصيغ تتغير من وقت لآخر؛ حدّث نسخة yt-dlp بشكل دوري للحفاظ على عمل هذه الميزة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }

        // Section 4: Final Download Button & Selector Status
        item {
            Spacer(modifier = Modifier.height(32.dp))

            val isEnabled = animatedSelectedFormat != null
            val buttonColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val contentColor = if (isEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline

            Button(
                onClick = {
                    animatedSelectedFormat?.let {
                        onStartDownload(
                            it.formatId,
                            it.ext,
                            selectedSubtitleLang,
                            sponsorblockAction,
                            selectedSponsorblockCategories,
                            if (isScheduled) scheduledAtMs else null
                        )
                    }
                },
                enabled = isEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = contentColor,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (isEnabled) 4.dp else 0.dp,
                    pressedElevation = if (isEnabled) 1.dp else 0.dp
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "زر التحميل",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (isEnabled) "بدء تحميل الجودة المحددة" else "اختر جودة فيديو للتحميل",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp)
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    itemCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = itemCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            Icon(
                imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) "تقليص" else "توسيع",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun FormatItemRow(
    format: FormatInfo,
    isSelected: Boolean,
    isMergedFormat: Boolean,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                Column {
                    val cleanRes = format.resolution
                        .replace("p", "")
                        .replace("x", " × ")
                        .trim()
                    
                    val formattedResolution = if (cleanRes.lowercase().contains("audio only")) {
                        "ملف صوتي"
                    } else if (cleanRes.isNotEmpty()) {
                        "$cleanRes px"
                    } else {
                        "دقة تلقائية"
                    }

                    Text(
                        text = formattedResolution,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = format.ext.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isMergedFormat && format.acodec.contains("none", ignoreCase = true)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "صوت + صورة ⚡",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = formatFileSize(format.filesizeApprox),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmptyCategoryLabel() {
    Text(
        text = "لا توجد صيغ متوافقة في هذا القسم",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        textAlign = TextAlign.Center
    )
}

/**
 * Beautiful duration text helper formatting seconds into logical video timeline tags.
 */
fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "مدي غير متاح"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

/**
 * Robust Byte converter matching requirements to human-readable memory counts.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "حجم تلقائي"
    val kcs = bytes / 1024.0
    val mcs = kcs / 1024.0
    val gcs = mcs / 1024.0
    return when {
        gcs >= 1.0 -> String.format("%.2f جيجابايت", gcs)
        mcs >= 1.0 -> String.format("%.1f ميجابايت", mcs)
        kcs >= 1.0 -> String.format("%.1f كيلوبايت", kcs)
        else -> "$bytes بايت"
    }
}

data class PlaylistTrackState(
    val entry: PlaylistEntry,
    val isSelected: Boolean = true,
    val convertToMp3: Boolean = false
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailsContentLayout(
    playlistTitle: String,
    entries: List<PlaylistEntry>,
    onDismiss: () -> Unit,
    onStartPlaylistDownloads: (items: List<Triple<PlaylistEntry, String, Boolean>>) -> Unit
) {
    val trackStates = remember {
        mutableStateListOf<PlaylistTrackState>().apply {
            addAll(entries.map { PlaylistTrackState(it) })
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .heightIn(max = 580.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp).padding(end = 8.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlistTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entries.size} فيديو في قائمة التشغيل",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "تطبيق الصيغة على كل العناصر:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            for (i in trackStates.indices) {
                                trackStates[i] = trackStates[i].copy(convertToMp3 = false)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                    ) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("فيديو للكل", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            for (i in trackStates.indices) {
                                trackStates[i] = trackStates[i].copy(convertToMp3 = true)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f))
                    ) {
                        Icon(Icons.Rounded.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("صوت MP3 للكل", fontSize = 12.sp)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            itemsIndexed(trackStates) { index, trackState ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (trackState.isSelected) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                        }
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = trackState.isSelected,
                            onCheckedChange = { checked ->
                                trackStates[index] = trackState.copy(isSelected = checked == true)
                            }
                        )

                        Card(
                            modifier = Modifier.size(width = 64.dp, height = 44.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            SubcomposeAsyncImage(
                                model = trackState.entry.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                loading = {
                                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                                }
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = trackState.entry.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatDuration(trackState.entry.duration),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = if (trackState.convertToMp3) "صوت 🎵" else "فيديو 🎬",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (trackState.convertToMp3) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                        fontSize = 11.sp
                                    )
                                    IconButton(
                                        onClick = {
                                            trackStates[index] = trackState.copy(convertToMp3 = !trackState.convertToMp3)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.SwapHoriz,
                                            contentDescription = "تغيير الصيغة",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val selectedCount = trackStates.count { it.isSelected }
        val isEnabled = selectedCount > 0

        Button(
            onClick = {
                val downloads = trackStates.filter { it.isSelected }.map {
                    Triple(it.entry, if (it.convertToMp3) "mp3" else "mp4", it.convertToMp3)
                }
                onStartPlaylistDownloads(downloads)
            },
            enabled = isEnabled,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(52.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Text(
                    text = if (isEnabled) "تأكيد وتحميل $selectedCount عنصر" else "الرجاء تحديد عنصر واحد على الأقل",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
