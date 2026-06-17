package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    ytdlpEngine: YtdlpEngine,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentFolderUri by settingsManager.defaultFolderUri.collectAsStateWithLifecycle(initialValue = null)
    val defaultQuality by settingsManager.defaultQuality.collectAsStateWithLifecycle(initialValue = "best")
    val themeStyle by settingsManager.themeStyle.collectAsStateWithLifecycle(initialValue = "system")

    var cookiesImported by remember { mutableStateOf(validateCookiesFileExists(context)) }

    // Folder Picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
                coroutineScope.launch {
                    settingsManager.setDefaultFolderUri(it.toString())
                }
                Toast.makeText(context, "تم حفظ المجلد الافتراضي بنجاح!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "فشل الحصول على صلاحيات المجلد: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Cookies Picker launcher
    val cookiesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val success = importCookiesFile(context, it)
            if (success) {
                cookiesImported = true
                Toast.makeText(context, "تم استيراد كوكيز متصفحك بنجاح!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "لم نتمكن من قراءة ملف cookies.txt", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val ytdlpVersion = remember { ytdlpEngine.getYtdlpVersion() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "إعدادات التطبيق",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section Title: Download Destination
            Text(
                text = "وجهة الحفظ ومخطط التخزين",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val folderLabel = if (!currentFolderUri.isNullOrEmpty()) {
                        val doc = DocumentFile.fromTreeUri(context, Uri.parse(currentFolderUri))
                        doc?.name ?: "مجلد مخصص تم اختياره"
                    } else {
                        "مسار التطبيق الافتراضي (Downloads sandbox)"
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp).padding(end = 8.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "مجلد الحفظ الافتراضي",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                folderLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تغيير المجلد via SAF")
                        }

                        if (!currentFolderUri.isNullOrEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        settingsManager.setDefaultFolderUri("")
                                    }
                                }
                            ) {
                                Text("إعادة تعيين")
                            }
                        }
                    }
                }
            }

            // Section Title: Preferences
            Text(
                text = "تفضيلات ومواصفات التحميل",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 1. Default Quality Dropdown
                    Column {
                        Text(
                            "دقة وجودة الملف الافتراضية",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "سيتم تطبيق الجودة تلقائيًا عند التحميل السريع بدون اختيار يدوي",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        var expandedQuality by remember { mutableStateOf(false) }
                        val qualityLabels = mapOf(
                            "best" to "أعلى جودة متاحة (فيديو + صوت)",
                            "medium" to "دقة متوسطة 720p/480p لتوفير البيانات",
                            "audio" to "استخراج الصوت فقط (MP3/M4A)"
                        )

                        Box {
                            OutlinedButton(
                                onClick = { expandedQuality = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(qualityLabels[defaultQuality] ?: "أعلى جودة متاحة")
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = expandedQuality,
                                onDismissRequest = { expandedQuality = false }
                            ) {
                                qualityLabels.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            coroutineScope.launch {
                                                settingsManager.setDefaultQuality(key)
                                            }
                                            expandedQuality = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // 2. Theme Mode Selection
                    Column {
                        Text(
                            "الوضع البصري (مظهر التطبيق)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))

                        val themes = listOf(
                            "system" to "تلقائي من النظام",
                            "light" to "الوضع المضيء",
                            "dark" to "الوضع الليلي"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            themes.forEach { (key, label) ->
                                val selected = themeStyle == key
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        coroutineScope.launch {
                                            settingsManager.setThemeStyle(key)
                                        }
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Section Title: Bypass Restrictions (Cookies)
            Text(
                text = "تجاوز القيود والحسابات (Cookies)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "محرك ملف الكوكيز (cookies.txt)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "يساعدك ملف الكوكيز على تحميل الفيديوهات المقيدة بفئات عمرية أو الحصرية للمتابعين والمشتركين على يوتيوب ومنصات أخرى.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Explanation reminder box
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp).padding(end = 6.dp)
                            )
                            Text(
                                "تنبيه أمان: يتم معالجة وتخزين ملف الكوكيز محليًا بالكامل داخل جهازك فقط لتمريره إلى محرك التحميل. لا يتم إرسال ملفات الكوكيز أو مشاركتها مع أي خوادم خارجية نهائيًا.",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { cookiesPickerLauncher.launch("text/*") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.ImportExport, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("استيراد cookies.txt")
                        }

                        if (cookiesImported) {
                            OutlinedButton(
                                onClick = {
                                    if (deleteCookiesFile(context)) {
                                        cookiesImported = false
                                        Toast.makeText(context, "تم إزالة ملف الكوكيز بنجاح", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("حذف الملف")
                            }
                        }
                    }

                    if (cookiesImported) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp, 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                )
                                Text(
                                    "ملف الكوكيز نشط وتلقائي المرور في محرك التحميل حالياً",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Section Title: About/System
            Text(
                text = "حول النظام والتحديثات",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "إصدار محرك التحمل (yt-dlp version)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "النسخة المضمنة حالياً: $ytdlpVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "تتغير خوارزميات يوتيوب والمواقع باستمرار، وتساعدك معرفة إصدار yt-dlp على تتبع التوافق ومزامنة تحديثات التطبيق عند الضرورة.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// Helpers for cookies.txt integration
private fun importCookiesFile(context: Context, uri: Uri): Boolean {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return false
        val bytes = inputStream.readBytes()
        inputStream.close()
        
        val cookiesFile = File(context.filesDir, "cookies.txt")
        cookiesFile.writeBytes(bytes)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

private fun validateCookiesFileExists(context: Context): Boolean {
    return File(context.filesDir, "cookies.txt").exists()
}

private fun deleteCookiesFile(context: Context): Boolean {
    return File(context.filesDir, "cookies.txt").delete()
}
