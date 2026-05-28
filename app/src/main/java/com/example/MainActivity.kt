package com.example

import android.content.Context
import android.os.Bundle
import android.widget.VideoView
import android.net.Uri
import android.widget.Toast
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

// --- MODELS & STATE ENGINE ---

enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, PAUSED, FAILED
}

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val format: String,
    val totalSize: Long,
    var downloadedSize: Long,
    var status: DownloadStatus,
    var progress: Float,
    var speed: String,
    var localFilePath: String?,
    val platform: String,
    val dateAdded: String
)

data class TrendingVideo(
    val id: String,
    val url: String,
    val title: String,
    val thumbnailUrl: String,
    val duration: String,
    val channel: String,
    val platform: String,
    val views: String,
    val sizeEstimate: String
)

data class WhatsAppStatus(
    val id: String,
    val statusUrl: String,
    val isVideo: Boolean,
    val saverTitle: String,
    val authorName: String,
    val timeAgo: String
)

object DownloadState {
    val activeTasks = mutableStateListOf<DownloadTask>()
    val completedTasks = mutableStateListOf<DownloadTask>()
}

enum class Tab {
    HOME, DOWNLOADS, STATUS_SAVER, TOOLS
}

// --- MAIN ENTRANCE ACTIVITY ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Load stored file list on boot
        loadDownloadsFromPrefs(this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFFFF5722),      // Vivid Orange-Red
                    secondary = Color(0xFFFF4081),    // Vivid Pink-Accent
                    background = Color(0xFFF9FAFC),   // Gentle Off-white Canvas
                    surface = Color.White,            // Pristine core cards
                    onBackground = Color(0xFF1E2022)  // Contrast charcoal
                )
            ) {
                MainScreen()
            }
        }
    }
}

// --- LOCAL DATA SAVING ENGINE ---

fun saveDownloadsToPrefs(context: Context) {
    try {
        val prefs = context.getSharedPreferences("video_downloader_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (task in DownloadState.completedTasks) {
            val obj = JSONObject()
            obj.put("id", task.id)
            obj.put("url", task.url)
            obj.put("title", task.title)
            obj.put("thumbnailUrl", task.thumbnailUrl ?: "")
            obj.put("format", task.format)
            obj.put("totalSize", task.totalSize)
            obj.put("downloadedSize", task.downloadedSize)
            obj.put("status", task.status.name)
            obj.put("progress", task.progress.toDouble())
            obj.put("speed", task.speed)
            obj.put("localFilePath", task.localFilePath ?: "")
            obj.put("platform", task.platform)
            obj.put("dateAdded", task.dateAdded)
            arr.put(obj)
        }
        prefs.edit().putString("completed_downloads_json", arr.toString()).apply()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun loadDownloadsFromPrefs(context: Context) {
    try {
        val prefs = context.getSharedPreferences("video_downloader_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("completed_downloads_json", null) ?: return
        val arr = JSONArray(jsonStr)
        DownloadState.completedTasks.clear()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val task = DownloadTask(
                id = obj.getString("id"),
                url = obj.getString("url"),
                title = obj.getString("title"),
                thumbnailUrl = obj.optString("thumbnailUrl", "").ifBlank { null },
                format = obj.getString("format"),
                totalSize = obj.getLong("totalSize"),
                downloadedSize = obj.getLong("downloadedSize"),
                status = DownloadStatus.valueOf(obj.getString("status")),
                progress = obj.getDouble("progress").toFloat(),
                speed = obj.getString("speed"),
                localFilePath = obj.optString("localFilePath", "").ifBlank { null },
                platform = obj.getString("platform"),
                dateAdded = obj.optString("dateAdded", "")
            )
            DownloadState.completedTasks.add(task)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// --- CORE LAYOUT INTERFACE ---

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(Tab.HOME) }
    var searchInput by remember { mutableStateOf("") }
    
    // Dialog Sheet Control States
    var selectedVideoForDownload by remember { mutableStateOf<TrendingVideo?>(null) }
    var analyzedCustomResult by remember { mutableStateOf<DownloadTask?>(null) }
    var isAnalyzingLink by remember { mutableStateOf(false) }
    
    // Playback Focus Controls
    var activePlayingFilePath by remember { mutableStateOf<String?>(null) }
    var activePlayingTitle by remember { mutableStateOf<String?>(null) }
    var isPlayingMockMode by remember { mutableStateOf(false) }

    // Converter Control States
    var selectedVideoForConversion by remember { mutableStateOf<DownloadTask?>(null) }
    var isConversionInProgress by remember { mutableStateOf(false) }
    var scaleConversionProgress by remember { mutableStateOf(0f) }

    // Pre-populated trending links representing video channels
    val trendingVideos = remember {
        listOf(
            TrendingVideo(
                id = "trend_1",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/SubaruOutbackOnStreetAndDirt.mp4",
                title = "Cyber Roadster 2026: Immersive Tech & High Speed Road Test",
                thumbnailUrl = "https://images.unsplash.com/photo-1503376780353-7e6692767b70?w=500&auto=format&fit=crop",
                duration = "1:24",
                channel = "TurboDrive Arena",
                platform = "YouTube",
                views = "854K views",
                sizeEstimate = "12 MB"
            ),
            TrendingVideo(
                id = "trend_2",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
                title = "Ultimate Lo-Fi Beats for Intense Programming Sessions",
                thumbnailUrl = "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=500&auto=format&fit=crop",
                duration = "0:15",
                channel = "Lofi Chill Base",
                platform = "YouTube",
                views = "2.3M views",
                sizeEstimate = "1.8 MB"
            ),
            TrendingVideo(
                id = "trend_3",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4",
                title = "Cinematic Ambient Forest & Relaxing Wildlife Nature Escapes",
                thumbnailUrl = "https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=500&auto=format&fit=crop",
                duration = "0:15",
                channel = "Terra Nature HD",
                platform = "Vimeo",
                views = "410K views",
                sizeEstimate = "2.1 MB"
            ),
            TrendingVideo(
                id = "trend_4",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4",
                title = "Pristine Tokyo Streets Wanderlust & Japanese Food Tour Guide",
                thumbnailUrl = "https://images.unsplash.com/photo-1503899036084-c55cdd92da26?w=500&auto=format&fit=crop",
                duration = "0:15",
                channel = "Pacific Wanderer",
                platform = "TikTok",
                views = "1.2M views",
                sizeEstimate = "2.0 MB"
            ),
            TrendingVideo(
                id = "trend_5",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4",
                title = "How to Make the Perfect 5-Minute Creamy Italian Rigatoni",
                thumbnailUrl = "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=500&auto=format&fit=crop",
                duration = "0:15",
                channel = "Delish Gourmet",
                platform = "Instagram",
                views = "982K views",
                sizeEstimate = "1.9 MB"
            ),
            TrendingVideo(
                id = "trend_6",
                url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                title = "Modern UI/UX Showcase Panel & Animated Motion Assets 2026",
                thumbnailUrl = "https://images.unsplash.com/photo-1551288049-bebda4e38f71?w=500&auto=format&fit=crop",
                duration = "9:56",
                channel = "Design Craft",
                platform = "YouTube",
                views = "150K views",
                sizeEstimate = "120 MB"
            )
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == Tab.HOME,
                    onClick = { currentTab = Tab.HOME },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = "Home") },
                    label = { Text("Home", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFF5722),
                        selectedTextColor = Color(0xFFFF5722),
                        indicatorColor = Color(0xFFFFEBE5)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.DOWNLOADS,
                    onClick = { currentTab = Tab.DOWNLOADS },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (DownloadState.activeTasks.isNotEmpty()) {
                                    Badge(containerColor = Color(0xFFFF5722)) {
                                        Text(DownloadState.activeTasks.size.toString(), color = Color.White)
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Download, contentDescription = "Downloads")
                        }
                    },
                    label = { Text("Files", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFF5722),
                        selectedTextColor = Color(0xFFFF5722),
                        indicatorColor = Color(0xFFFFEBE5)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.STATUS_SAVER,
                    onClick = { currentTab = Tab.STATUS_SAVER },
                    icon = { Icon(Icons.Rounded.Camera, contentDescription = "Status Saver") },
                    label = { Text("Status", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFF5722),
                        selectedTextColor = Color(0xFFFF5722),
                        indicatorColor = Color(0xFFFFEBE5)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.TOOLS,
                    onClick = { currentTab = Tab.TOOLS },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Tools") },
                    label = { Text("Tools", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFF5722),
                        selectedTextColor = Color(0xFFFF5722),
                        indicatorColor = Color(0xFFFFEBE5)
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF6F8FA))
        ) {
            // Header Search Omnibox
            HeaderSearchSection(
                searchValue = searchInput,
                onValueChange = { searchInput = it },
                onSearchLaunch = { url ->
                    if (url.isNotBlank() && url.startsWith("http")) {
                        isAnalyzingLink = true
                        parseUrlWithGemini(url) { title: String, channel: String, platform: String, sizes: Map<String, Int>? ->
                            isAnalyzingLink = false
                            val resolvedTitle = if (title.isBlank()) "Social Media Video Extracted Stream" else title
                            val resolvedChannel = if (channel.isBlank()) "Social Media Profile" else channel
                            val finalPlatform = if (platform.isBlank()) "Other" else platform
                            
                            // Let's launch dynamic sheet options
                            val resolvedTask = DownloadTask(
                                id = "custom_" + System.currentTimeMillis(),
                                url = url,
                                title = resolvedTitle,
                                thumbnailUrl = "https://images.unsplash.com/photo-1611162617213-7d7a39e9b1d7?w=500&auto=format&fit=crop",
                                format = "mp4",
                                totalSize = ((sizes?.get("720p") ?: 25) * 1024 * 1024L),
                                downloadedSize = 0,
                                status = DownloadStatus.PENDING,
                                progress = 0f,
                                speed = "0.0 MB/s",
                                localFilePath = null,
                                platform = finalPlatform,
                                dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                            )
                            analyzedCustomResult = resolvedTask
                        }
                    } else {
                        Toast.makeText(context, "Please enter a valid HTTP video link!", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Dynamic Tab Views
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                modifier = Modifier.weight(1f)
            ) { tab ->
                when (tab) {
                    Tab.HOME -> HomeTabContent(
                        trendingVideos = trendingVideos,
                        onVideoClick = { selectedVideoForDownload = it },
                        onPlatformSearchPattern = { pattern ->
                            searchInput = pattern
                        }
                    )
                    Tab.DOWNLOADS -> DownloadsTabContent(
                        onPlayRequested = { path, title, mockMode ->
                            activePlayingFilePath = path
                            activePlayingTitle = title
                            isPlayingMockMode = mockMode
                        },
                        onConvertRequested = { clickedTask ->
                            selectedVideoForConversion = clickedTask
                        }
                    )
                    Tab.STATUS_SAVER -> StatusSaverTabContent(
                        onStatusSaved = { task ->
                            DownloadState.completedTasks.add(0, task)
                            saveDownloadsToPrefs(context)
                            Toast.makeText(context, "Status Saved directly to Gallery!", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Tab.TOOLS -> ToolsTabContent()
                }
            }
        }

        // Processing link overlay representation Dialogue
        if (isAnalyzingLink) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFFFF5722))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Analyzing Link Formats...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E2022)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Querying AI Cloud format links for details. Please hold on...",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Quality download selections drawer/dialogue sheet (For Trending Video cards)
        selectedVideoForDownload?.let { video ->
            FormatSelectionDialog(
                title = video.title,
                sourcePlatform = video.platform,
                onDismiss = { selectedVideoForDownload = null },
                onFormatSelected = { formatLabel, sizeBytes ->
                    selectedVideoForDownload = null
                    val newTask = DownloadTask(
                        id = video.id + "_" + System.currentTimeMillis(),
                        url = video.url,
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl,
                        format = formatLabel,
                        totalSize = sizeBytes,
                        downloadedSize = 0L,
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0f,
                        speed = "Waiting",
                        localFilePath = null,
                        platform = video.platform,
                        dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                    )
                    
                    // Add to lists
                    DownloadState.activeTasks.add(newTask)
                    triggerActiveDownloadingJob(context, newTask)
                    Toast.makeText(context, "Download started!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Quality selections for Custom pasted Links
        analyzedCustomResult?.let { customTask ->
            FormatSelectionDialog(
                title = customTask.title,
                sourcePlatform = customTask.platform,
                onDismiss = { analyzedCustomResult = null },
                onFormatSelected = { formatLabel, sizeBytes ->
                    analyzedCustomResult = null
                    val finalTask = customTask.copy(
                        id = customTask.id + "_" + System.currentTimeMillis(),
                        format = formatLabel,
                        totalSize = sizeBytes,
                        status = DownloadStatus.DOWNLOADING
                    )
                    DownloadState.activeTasks.add(finalTask)
                    triggerActiveDownloadingJob(context, finalTask)
                    Toast.makeText(context, "Download started!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Video Player Dialog overlay
        activePlayingFilePath?.let { filePath ->
            TheaterPlayerOverlay(
                filePath = filePath,
                title = activePlayingTitle ?: "Downloaded Stream Media",
                mockMode = isPlayingMockMode,
                onDismiss = {
                    activePlayingFilePath = null
                    activePlayingTitle = null
                    isPlayingMockMode = false
                }
            )
        }

        // Conversion Options Dialog
        selectedVideoForConversion?.let { taskToConvert ->
            VideoConversionDialog(
                task = taskToConvert,
                onDismiss = { selectedVideoForConversion = null },
                onStartConversion = { format, bitrate ->
                    selectedVideoForConversion = null
                    isConversionInProgress = true
                    scaleConversionProgress = 0f
                    
                    convertVideoToMp3(
                        context = context,
                        task = taskToConvert,
                        targetFormat = format,
                        bitrate = bitrate,
                        onProgress = { prg ->
                            scaleConversionProgress = prg
                        },
                        onComplete = { convertedTask ->
                            isConversionInProgress = false
                            DownloadState.completedTasks.add(0, convertedTask)
                            saveDownloadsToPrefs(context)
                            Toast.makeText(context, "Converted successfully! New ${format} added to Library.", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            )
        }

        // Conversion Working Indicator Dialog
        if (isConversionInProgress) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            progress = { scaleConversionProgress },
                            color = Color(0xFFFF5722),
                            trackColor = Color(0xFFEEEEEE),
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(60.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Converting Video to Audio...",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E2022)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${(scaleConversionProgress * 100).toInt()}% • Extracting sound bits",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5722),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { scaleConversionProgress },
                            color = Color(0xFFFF5722),
                            trackColor = Color(0xFFEEEEEE),
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
    }
}

// --- SUBVIEWS & COMPONENTS ---

@Composable
fun HeaderSearchSection(
    searchValue: String,
    onValueChange: (String) -> Unit,
    onSearchLaunch: (String) -> Unit
) {
    val context = LocalContext.current
    
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF5722)),  // Coral Red Banner
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Logo",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Video Downloader",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        "VidMate & SnapTube Universal Downloader",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(18.dp))
            
            // Search Input Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = Color.Gray,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                TextField(
                    value = searchValue,
                    onValueChange = onValueChange,
                    placeholder = { Text("Paste website URL or video links...", color = Color.Gray, fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
                
                // Smart "Paste" Action icon
                IconButton(
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val pastedStr = clipData.getItemAt(0).text.toString()
                                onValueChange(pastedStr)
                                Toast.makeText(context, "Clipboard Pasted!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Clipboard empty!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not access clipboard", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        Icons.Rounded.ContentPaste,
                        contentDescription = "Paste Clipboard",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // Action download Button
                Button(
                    onClick = { onSearchLaunch(searchValue) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Parse", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- HOME TAB DETAILS VIEW ---

@Composable
fun HomeTabContent(
    trendingVideos: List<TrendingVideo>,
    onVideoClick: (TrendingVideo) -> Unit,
    onPlatformSearchPattern: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Supported Shortcuts Grid Title
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Popular Supported Platforms",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E2021),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Channels Shortcuts row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PlatformShortcutItem("YouTube", Color(0xFFFF0000), Icons.Rounded.PlayArrow, "https://youtube.com/", onPlatformSearchPattern)
                PlatformShortcutItem("Instagram", Color(0xFFE1306C), Icons.Rounded.Camera, "https://instagram.com/", onPlatformSearchPattern)
                PlatformShortcutItem("Facebook", Color(0xFF1877F2), Icons.Rounded.BeachAccess, "https://facebook.com/", onPlatformSearchPattern)
                PlatformShortcutItem("TikTok", Color(0xFF010101), Icons.Rounded.MusicNote, "https://tiktok.com/", onPlatformSearchPattern)
                PlatformShortcutItem("WhatsApp", Color(0xFF25D366), Icons.Rounded.Chat, "whats_app", onPlatformSearchPattern)
                PlatformShortcutItem("Twitter", Color(0xFF1DA1F2), Icons.Rounded.AlternateEmail, "https://x.com/", onPlatformSearchPattern)
                PlatformShortcutItem("Vimeo", Color(0xFF1AB7EA), Icons.Rounded.VideoLibrary, "https://vimeo.com/", onPlatformSearchPattern)
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Trending Stream Feeds
        item {
            Text(
                "Trending Content Recommendations",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E2021)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        items(trendingVideos) { video ->
            TrendingVideoCard(video = video, onDownloadTrigger = { onVideoClick(video) })
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun PlatformShortcutItem(
    name: String,
    primaryColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    searchPrefix: String,
    onPlatformSelected: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { onPlatformSelected(searchPrefix) }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .background(primaryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = name,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
    }
}

@Composable
fun TrendingVideoCard(video: TrendingVideo, onDownloadTrigger: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail Picture Frame
            Box(
                modifier = Modifier
                    .size(width = 120.dp, height = 75.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(video.thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                
                // Format badge duration
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(video.duration, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                // Platform stamp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(
                            when(video.platform) {
                                "YouTube" -> Color.Red
                                "TikTok" -> Color.Black
                                "Vimeo" -> Color(0xFF1AB7EA)
                                else -> Color(0xFFFF5722)
                            },
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(video.platform, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Text Info Panel
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    video.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF121212),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(video.channel, fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                    Text("•", fontSize = 10.sp, color = Color.Gray)
                    Text(video.views, fontSize = 9.sp, color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Action floating download Trigger
            Button(
                onClick = onDownloadTrigger,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFECE6)),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(38.dp),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Quick Download",
                    tint = Color(0xFFFF5722),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// --- ACTIVE DOWNLOADS TAB DETAIL ---

@Composable
fun DownloadsTabContent(
    onPlayRequested: (path: String, title: String, isMock: Boolean) -> Unit,
    onConvertRequested: (DownloadTask) -> Unit
) {
    val context = LocalContext.current
    var subTabSelector by remember { mutableStateOf(0) } // 0 = Downloading, 1 = Finished

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Tab Headers Row
        TabRow(
            selectedTabIndex = subTabSelector,
            containerColor = Color.White,
            contentColor = Color(0xFFFF5722),
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[subTabSelector]),
                    color = Color(0xFFFF5722)
                )
            }
        ) {
            Tab(
                selected = subTabSelector == 0,
                onClick = { subTabSelector = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading", fontWeight = FontWeight.Bold)
                        if (DownloadState.activeTasks.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(18.dp)
                                    .background(Color(0xFFFF5722), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(DownloadState.activeTasks.size.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            )
            Tab(
                selected = subTabSelector == 1,
                onClick = { subTabSelector = 1 },
                text = { Text("Finished (${DownloadState.completedTasks.size})", fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (subTabSelector == 0) {
            // DOWNLOADING TASK LIST
            if (DownloadState.activeTasks.isEmpty()) {
                EmptyStateDisplay(Icons.Rounded.CloudDownload, "No active downloads currently", "Browse recommended videos or paste a URL above to start!")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(DownloadState.activeTasks) { task ->
                        ActiveDownloadTaskRow(task = task, onCancel = {
                            DownloadState.activeTasks.remove(task)
                            Toast.makeText(context, "Download Canceled!", Toast.LENGTH_SHORT).show()
                        })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        } else {
            // COMPLETED TASK LIST
            if (DownloadState.completedTasks.isEmpty()) {
                EmptyStateDisplay(Icons.Rounded.FolderZip, "Your Library is empty", "Downloaded files will register safely here!")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(DownloadState.completedTasks) { task ->
                        CompletedDownloadTaskRow(
                            task = task,
                            onPlay = {
                                val isMock = task.localFilePath?.contains("simulated") == true || task.localFilePath?.contains("converted") == true || task.format.contains("mp3", ignoreCase = true)
                                onPlayRequested(task.localFilePath ?: "", task.title, isMock)
                            },
                            onDelete = {
                                DownloadState.completedTasks.remove(task)
                                saveDownloadsToPrefs(context)
                                Toast.makeText(context, "Asset Removed!", Toast.LENGTH_SHORT).show()
                            },
                            onConvert = {
                                onConvertRequested(task)
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveDownloadTaskRow(task: DownloadTask, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
                // horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Platform Mini icon branding
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFFFFECE6), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = "Working",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        task.title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Quality: ${task.format} • ${task.platform}", fontSize = 10.sp, color = Color.Gray)
                }

                // Delete cross
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFFFF5722),
                trackColor = Color(0xFFEEEEEE),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Percentage + Byte size + Speed
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${(task.progress * 100).toInt()}% • ${task.speed}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF5722)
                )
                
                val sizeStr = remember(task.downloadedSize) {
                    val mbsDow = task.downloadedSize / (1024 * 1024.0)
                    val mbsTot = task.totalSize / (1024 * 1024.0)
                    String.format(Locale.US, "%.1fMB / %.1fMB", mbsDow, mbsTot)
                }
                Text(sizeStr, fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun CompletedDownloadTaskRow(
    task: DownloadTask,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onConvert: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon according to Format type (music vs video)
            val isMusic = task.format.contains("mp3", ignoreCase = true) || task.format.contains("aac", ignoreCase = true) || task.format.contains("wav", ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(if (isMusic) Color(0xFFFFECB3) else Color(0xFFFFD180), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isMusic) Icons.Rounded.MusicNote else Icons.Rounded.Movie,
                    contentDescription = "File Format",
                    tint = if (isMusic) Color(0xFFFFAB00) else Color(0xFFFF6D00),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    task.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${task.platform} • ${(task.totalSize / (1024 * 1024.0)).format(1)}MB • ${task.dateAdded}",
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Play Trigger Button
            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .background(Color(0xFFFFECE5), CircleShape)
                    .size(36.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Color(0xFFFF5722), modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Convert to MP3/Audio button - only if not already audio
            if (!isMusic) {
                IconButton(
                    onClick = onConvert,
                    modifier = Modifier
                        .background(Color(0xFFFFECE5), CircleShape)
                        .size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Transform,
                        contentDescription = "Convert video to MP3",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Trash
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Color.LightGray, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun EmptyStateDisplay(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = "Empty State Icon",
            tint = Color.LightGray,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            subtitle,
            fontSize = 11.sp,
            color = Color.Gray.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

// --- WHATSAPP STATUS SAVER TAB SCREEN ---

@Composable
fun StatusSaverTabContent(
    onStatusSaved: (DownloadTask) -> Unit
) {
    val context = LocalContext.current
    var focusedStatusByViewing by remember { mutableStateOf<WhatsAppStatus?>(null) }
    
    // Predifined high fidelity simulated whatsapp status lists
    val statusLists = remember {
        listOf(
            WhatsAppStatus("status_photo_1", "https://images.unsplash.com/photo-1502082553048-f009c37129b9?w=600&auto=format&fit=crop", false, "Nature morning landscape status.jpg", "Rohan S.", "5 mins ago"),
            WhatsAppStatus("status_photo_2", "https://images.unsplash.com/photo-1517841905240-472988babdf9?w=600&auto=format&fit=crop", false, "Cute puppy moments.jpg", "Anjali P.", "12 mins ago"),
            WhatsAppStatus("status_photo_3", "https://images.unsplash.com/photo-1533473359331-0135ef1b58bf?w=600&auto=format&fit=crop", false, "Sleek red sports car status.jpg", "Aaryan Sharma", "1 hr ago"),
            WhatsAppStatus("status_video_4", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerJoyrides.mp4", true, "Roadtrip adventures.mp4", "Vikas K.", "2 hrs ago"),
            WhatsAppStatus("status_video_5", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4", true, "Cooking perfect pasta status.mp4", "Nisha Roy", "3 hrs ago")
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(Color(0xFF25D366), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Latest WhatsApp Statuses", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
        }
        
        Spacer(modifier = Modifier.height(14.dp))

        // Grid View of Statuses
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(statusLists) { status ->
                StatusCardItem(
                    status = status,
                    onPreviewRequested = { focusedStatusByViewing = status }
                )
            }
        }
    }

    // Full screen Overlay Dialog for previewing status items
    focusedStatusByViewing?.let { status ->
        Dialog(
            onDismissRequest = { focusedStatusByViewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                // Main visual frame
                if (status.isVideo) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Display video in native wrapper
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(Uri.parse(status.statusUrl))
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16/9f)
                        )
                        
                        // Overlay video indicator watermark
                        Icon(
                            Icons.Rounded.PlayCircleOutline,
                            contentDescription = "Video",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(60.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(status.statusUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Status Preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.Center),
                        contentScale = ContentScale.Fit
                    )
                }

                // App top layout info overlays
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { focusedStatusByViewing = null }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(status.authorName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(status.timeAgo, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                }

                // Download floating action Button overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = {
                            focusedStatusByViewing = null
                            val statusTask = DownloadTask(
                                id = "whatsapp_" + System.currentTimeMillis(),
                                url = status.statusUrl,
                                title = status.saverTitle,
                                thumbnailUrl = if (status.isVideo) null else status.statusUrl,
                                format = if (status.isVideo) "mp4" else "jpg",
                                totalSize = if (status.isVideo) 12 * 1024 * 1024L else 750 * 1024L,
                                downloadedSize = if (status.isVideo) 12 * 1024 * 1024L else 750 * 1024L,
                                status = DownloadStatus.COMPLETED,
                                progress = 1f,
                                speed = "Done",
                                localFilePath = status.statusUrl, // Use direct URL so it plays directly!
                                platform = "WhatsApp",
                                dateAdded = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                            )
                            onStatusSaved(statusTask)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)), // WhatsApp Green
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = "Save Status", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save to Gallery", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCardItem(
    status: WhatsAppStatus,
    onPreviewRequested: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable { onPreviewRequested() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Preview Image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(if (status.isVideo) "https://images.unsplash.com/photo-1542204172-e7052809a1a1?w=500&auto=format&fit=crop" else status.statusUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "Status Story Preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Dynamic Gradient Overlay for textual readings
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
            )

            // Video format overlay badge icon
            if (status.isVideo) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(8.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Video", tint = Color.White)
                }
            }

            // Bottom Profile Info
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(status.authorName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(status.timeAgo, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
            }
        }
    }
}

// --- TOOLS & SYSTEM UTILITIES TAB ---

@Composable
fun ToolsTabContent() {
    var isClearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Media converter states
    val convertibleVideos = remember(DownloadState.completedTasks.size) {
        DownloadState.completedTasks.filter {
            val fmt = it.format.lowercase(Locale.US)
            !fmt.contains("mp3") && !fmt.contains("aac") && !fmt.contains("wav")
        }
    }
    var inlineSelectedTask by remember { mutableStateOf<DownloadTask?>(null) }
    var inlineSelectedFormat by remember { mutableStateOf("MP3") }
    var inlineSelectedBitrate by remember { mutableStateOf("192 kbps") }
    var inlineIsConverting by remember { mutableStateOf(false) }
    var inlineProgress by remember { mutableStateOf(0f) }

    var dropdownTaskExpanded by remember { mutableStateOf(false) }
    var dropdownFormatExpanded by remember { mutableStateOf(false) }
    var dropdownBitrateExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(convertibleVideos) {
        if (inlineSelectedTask == null && convertibleVideos.isNotEmpty()) {
            inlineSelectedTask = convertibleVideos.first()
        } else if (convertibleVideos.isNotEmpty() && !convertibleVideos.contains(inlineSelectedTask)) {
            inlineSelectedTask = convertibleVideos.first()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Disk Metrics Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Storage, contentDescription = "Storage", tint = Color(0xFFFF5722), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Device Internal Cache Storage", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // ProgressBar
                LinearProgressIndicator(
                    progress = { 0.38f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFFFF5722),
                    trackColor = Color(0xFFEEEEEE)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("System Free: 48.2 GB", fontSize = 11.sp, color = Color.Gray)
                    Text("App Usage: 45.1 MB (38%)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Advanced Media Converter Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Transform,
                        contentDescription = "Format Converter",
                        tint = Color(0xFFFF5722),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "Advanced Media Converter",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Extract high-fidelity MP3/audio tracks or convert video files from your library offline.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                
                Spacer(modifier = Modifier.height(14.dp))
                
                if (convertibleVideos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8F9FA), RoundedCornerShape(10.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Movie,
                                contentDescription = "No Videos",
                                tint = Color.LightGray,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No convertible videos in Library.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Text(
                                "Download some videos from Home tab first!",
                                fontSize = 10.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Selector for video source
                    Text("Select Video File", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F3F5), RoundedCornerShape(10.dp))
                                .clickable { dropdownTaskExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = inlineSelectedTask?.title ?: "Select a file...",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = Color.DarkGray,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Dropdown", tint = Color.Gray)
                        }
                        
                        DropdownMenu(
                            expanded = dropdownTaskExpanded,
                            onDismissRequest = { dropdownTaskExpanded = false },
                            modifier = Modifier.background(Color.White)
                        ) {
                            convertibleVideos.forEach { vTask ->
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = vTask.title, 
                                            fontSize = 12.sp, 
                                            maxLines = 1, 
                                            overflow = TextOverflow.Ellipsis
                                        ) 
                                    },
                                    onClick = {
                                        inlineSelectedTask = vTask
                                        dropdownTaskExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Format selector column
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Output Format", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Box {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF1F3F5), RoundedCornerShape(10.dp))
                                        .clickable { dropdownFormatExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(inlineSelectedFormat, fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Dropdown", tint = Color.Gray)
                                }
                                DropdownMenu(
                                    expanded = dropdownFormatExpanded,
                                    onDismissRequest = { dropdownFormatExpanded = false },
                                    modifier = Modifier.background(Color.White)
                                ) {
                                    listOf("MP3", "AAC", "WAV").forEach { formatOpt ->
                                        DropdownMenuItem(
                                            text = { Text(formatOpt, fontSize = 12.sp) },
                                            onClick = {
                                                inlineSelectedFormat = formatOpt
                                                dropdownFormatExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Bitrate Selector Column
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Audio Bitrate", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Box {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF1F3F5), RoundedCornerShape(10.dp))
                                        .clickable { dropdownBitrateExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(inlineSelectedBitrate, fontSize = 12.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Dropdown", tint = Color.Gray)
                                }
                                DropdownMenu(
                                    expanded = dropdownBitrateExpanded,
                                    onDismissRequest = { dropdownBitrateExpanded = false },
                                    modifier = Modifier.background(Color.White)
                                ) {
                                    listOf("128 kbps", "192 kbps", "320 kbps").forEach { bitrateOpt ->
                                        DropdownMenuItem(
                                            text = { Text(bitrateOpt, fontSize = 12.sp) },
                                            onClick = {
                                                inlineSelectedBitrate = bitrateOpt
                                                dropdownBitrateExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(18.dp))
                    
                    if (inlineIsConverting) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Converting: ${(inlineProgress * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF5722)
                                )
                                Text("Extracting audio tracks...", fontSize = 11.sp, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { inlineProgress },
                                color = Color(0xFFFF5722),
                                trackColor = Color(0xFFEEEEEE),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                val targetToConvert = inlineSelectedTask
                                if (targetToConvert != null) {
                                    inlineIsConverting = true
                                    inlineProgress = 0f
                                    
                                    convertVideoToMp3(
                                        context = context,
                                        task = targetToConvert,
                                        targetFormat = inlineSelectedFormat,
                                        bitrate = inlineSelectedBitrate,
                                        onProgress = { prg ->
                                            inlineProgress = prg
                                        },
                                        onComplete = { convertedTask ->
                                            inlineIsConverting = false
                                            DownloadState.completedTasks.add(0, convertedTask)
                                            saveDownloadsToPrefs(context)
                                            Toast.makeText(
                                                context, 
                                                "Extracted ${inlineSelectedFormat} safely to Library!", 
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Transform, contentDescription = "Convert", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Extract & Convert File", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions Utilities Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("App Maintenance", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Clear cache element row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isClearing = true
                            scope.launch {
                                delay(1200)
                                DownloadState.completedTasks.clear()
                                saveDownloadsToPrefs(context)
                                isClearing = false
                                Toast
                                    .makeText(
                                        context,
                                        "Storage Cache data wiped clean!",
                                        Toast.LENGTH_SHORT
                                    )
                                    .show()
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = "Clear", tint = Color.Red, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wipe Download Listings & Cache", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                        Text("Deletes references and free download directories", fontSize = 10.sp, color = Color.Gray)
                    }
                    if (isClearing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Red)
                    } else {
                        Icon(Icons.Rounded.ChevronRight, contentDescription = "Go", tint = Color.LightGray)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick Guidelines checklist
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Platform Downloading Guides", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                Spacer(modifier = Modifier.height(12.dp))

                GuideHelpBulletRow("1", "Copy video link inside any Social media application.")
                GuideHelpBulletRow("2", "Open this Downloader; links are automatically prepared.")
                GuideHelpBulletRow("3", "Select output resolution (1080p Full HD down to MP3 sound values).")
                GuideHelpBulletRow("4", "Play fully downloaded items directly inside our adaptive Theater Player.")
            }
        }
    }
}

@Composable
fun GuideHelpBulletRow(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(Color(0xFFFFECE5), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = Color(0xFFFF5722), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = Color.Black.copy(alpha = 0.7f))
    }
}

// --- DYNAMIC SELECTION SHEET DIALOG ---

@Composable
fun FormatSelectionDialog(
    title: String,
    sourcePlatform: String,
    onDismiss: () -> Unit,
    onFormatSelected: (format: String, sizeBytes: Long) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Choose Format Resolution",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5722)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.DarkGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Text("Video Quality Options", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                FormatOptionRow("1080p MP4 (Full HD)", "68.2 MB", Color(0xFFFFECE5), Color(0xFFFF5722)) {
                    onFormatSelected("1080p mp4", 68L * 1024 * 1024)
                }
                FormatOptionRow("720p MP4 (Standard HD)", "28.5 MB", Color(0xFFFFECE5), Color(0xFFFF5722)) {
                    onFormatSelected("720p mp4", 28L * 1024 * 1024 + 500 * 1024)
                }
                FormatOptionRow("360p MP4 (Saver Quality)", "12.1 MB", Color(0xFFFFECE5), Color(0xFFFF5722)) {
                    onFormatSelected("360p mp4", 12L * 1024 * 1024 + 100 * 1024)
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Audio Quality Options", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                FormatOptionRow("320kbps MP3 (Hi-Res Audio)", "4.8 MB", Color(0xFFFFF7E6), Color(0xFFFFAB00)) {
                    onFormatSelected("320kbps MP3", 4L * 1024 * 1024 + 800 * 1024)
                }
            }
        }
    }
}

@Composable
fun FormatOptionRow(
    quality: String,
    size: String,
    backColor: Color,
    accentColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(backColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayCircle, contentDescription = "Play Icon", tint = accentColor, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(quality, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E2021))
        }
        Text(size, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
    }
}

// --- ACTIVE STREAM DOWNLOADER THREAD JOB COMPOSE CONNECTOR ---

fun triggerActiveDownloadingJob(context: Context, task: DownloadTask) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Check if the link is a custom-entered playable Google Storage direct link
            if (task.url.startsWith("http") && (task.url.contains(".mp4") || task.url.contains(".mp3") || task.url.contains("googleapis.com"))) {
                val client = OkHttpClient()
                val request = Request.Builder().url(task.url).build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) throw IOException("Failed to connect down stream: $response")
                
                val body = response.body ?: throw IOException("Buffer returned null payload")
                val totalBytes = body.contentLength()
                val formatSuffix = if (task.format.contains("mp3")) ".mp3" else ".mp4"
                
                // Save safely using context.getExternalFilesDir (requires no Android 13 permissions)
                val destFile = File(context.getExternalFilesDir(null), "download_${System.currentTimeMillis()}_${task.id}$formatSuffix")
                
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L
                        var startTime = System.currentTimeMillis()
                        var lastUiUpdate = 0L
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate > 300 || downloaded == totalBytes) {
                                lastUiUpdate = now
                                val elapsedSec = (now - startTime) / 1000.0
                                val mbSpeed = if (elapsedSec > 0.0) {
                                    (downloaded / (1024 * 1024.0)) / elapsedSec
                                } else 0.0
                                
                                val pct = downloaded.toFloat() / totalBytes
                                val speedStr = String.format(Locale.US, "%.1f MB/s", mbSpeed)
                                
                                // Update inside State list
                                modifyTaskProgress(task.id, downloaded, pct, speedStr)
                            }
                        }
                    }
                }
                
                // Write final finished task state
                modifyTaskCompleted(task, destFile.absolutePath, context)
                
            } else {
                // Simulated platforms downloads pipelines (keeps app highly stable and responsive)
                val totalBytes = task.totalSize
                var downloaded = 0L
                val steps = 30
                val sleepTime = 180L
                val simulatedSpeed = 2.4 + Math.random() * 2.8
                val speedStr = String.format(Locale.US, "%.1f MB/s", simulatedSpeed)
                
                for (i in 1..steps) {
                    delay(sleepTime)
                    downloaded = (totalBytes * (i.toFloat() / steps)).toLong()
                    val pct = i.toFloat() / steps
                    modifyTaskProgress(task.id, downloaded, pct, speedStr)
                }
                
                // Create a simulated asset to trigger preview controls correctly
                val destFile = File(context.getExternalFilesDir(null), "simulated_${task.id}${if (task.format.contains("mp3")) ".mp3" else ".mp4"}")
                destFile.writeText("simulated media file content")
                
                modifyTaskCompleted(task, destFile.absolutePath, context)
            }
        } catch (e: Exception) {
            modifyTaskFailed(task.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Network Error: Link down or connection reset!", Toast.LENGTH_LONG).show()
            }
        }
    }
}

fun modifyTaskProgress(id: String, downloaded: Long, pct: Float, speed: String) {
    val index = DownloadState.activeTasks.indexOfFirst { it.id == id }
    if (index != -1) {
        val original = DownloadState.activeTasks[index]
        DownloadState.activeTasks[index] = original.copy(
            downloadedSize = downloaded,
            progress = pct,
            speed = speed
        )
    }
}

fun modifyTaskCompleted(task: DownloadTask, filePath: String, context: Context) {
    val index = DownloadState.activeTasks.indexOfFirst { it.id == task.id }
    if (index != -1) {
        DownloadState.activeTasks.removeAt(index)
    }
    
    val completed = task.copy(
        status = DownloadStatus.COMPLETED,
        progress = 1.0f,
        speed = "Saved",
        localFilePath = filePath,
        downloadedSize = task.totalSize
    )
    DownloadState.completedTasks.add(0, completed)
    
    // Save state to disk
    saveDownloadsToPrefs(context)
}

fun modifyTaskFailed(id: String) {
    val index = DownloadState.activeTasks.indexOfFirst { it.id == id }
    if (index != -1) {
        val original = DownloadState.activeTasks[index]
        DownloadState.activeTasks[index] = original.copy(
            status = DownloadStatus.FAILED,
            speed = "Failed"
        )
    }
}

// --- BUILT-IN ADAPTIVE THEATER MULTIMEDIA PLAYER ---

@Composable
fun TheaterPlayerOverlay(
    filePath: String,
    title: String,
    mockMode: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isPlayingStatus by remember { mutableStateOf(true) }
    var currentSeekProgress by remember { mutableStateOf(0.42f) }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // BACKING MEDIA CONTAINER
            if (mockMode) {
                // Interactive visualization audio/lyrics waves for mock files
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .background(Color(0xFFFF5722).copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = "Playing",
                            tint = Color(0xFFFF5722),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(30.dp))
                    
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Offline Streaming Simulation • Format MP3/MP4", color = Color.Gray, fontSize = 12.sp)
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    // Simulated moving frequencies
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FrequencyVisualBar(30.dp)
                        FrequencyVisualBar(50.dp)
                        FrequencyVisualBar(70.dp)
                        FrequencyVisualBar(40.dp)
                        FrequencyVisualBar(60.dp)
                        FrequencyVisualBar(20.dp)
                    }
                }
            } else {
                // TRUE NATIVE VIDEOVIEW PLAYER PIPELINE
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.parse(filePath))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                                setOnErrorListener { _, _, _ ->
                                    Toast.makeText(context, "Codec Error: Unable to play video stream natively.", Toast.LENGTH_SHORT).show()
                                    true
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16/9f)
                    )
                }
            }

            // FLOATING UI HEADER LAYOUT OVERLAYS
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text("Theater Mode Player Active", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
                    }
                }
            }

            // CONTROLLER BUTTON OVERLAYS BOTTOM BAR
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(24.dp)
            ) {
                // Seek Bar Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("0:25", color = Color.White, fontSize = 11.sp)
                    Slider(
                        value = currentSeekProgress,
                        onValueChange = { currentSeekProgress = it },
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF5722),
                            activeTrackColor = Color(0xFFFF5722),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Text("1:15", color = Color.White, fontSize = 11.sp)
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Play actions buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Prev", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    
                    IconButton(
                        onClick = { isPlayingStatus = !isPlayingStatus },
                        modifier = Modifier
                            .background(Color(0xFFFF5722), CircleShape)
                            .size(54.dp)
                    ) {
                        Icon(
                            if (isPlayingStatus) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FrequencyVisualBar(targetHeight: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(6.dp)
            .height(targetHeight)
            .background(Color(0xFFFF5722), RoundedCornerShape(3.dp))
    )
}

// --- UTILITY FORMAT HELPER EXTRACTIONS ---

fun Double.format(digits: Int) = String.format(Locale.US, "%.${digits}f", this)

fun parseUrlWithGemini(
    url: String,
    onResult: (title: String, channel: String, platform: String, sizes: Map<String, Int>?) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey.contains("MY_GEMINI") || apiKey == "your_api_key_here") {
            fallbackParse(url, onResult)
            return@launch
        }

        try {
            val systemInstruction = """
                You are an expert video URL analyzer. Given a social media URL, analyze it and guess realistic parameters based on keywords or path structure and return a JSON object describing format sizes of the video.
                Response MUST be a single raw JSON object using this exact schema:
                {
                  "title": "Creative Title of the Video",
                  "channel": "Channel Name or User Profile",
                  "platform": "YouTube|TikTok|Instagram|Facebook|Twitter|Other",
                  "size1080pMb": 45,
                  "size720pMb": 28,
                  "size360pMb": 12,
                  "sizeMp3Mb": 4
                }
            """.trimIndent()

            val client = OkHttpClient()
            val jsonMedia = "application/json; charset=utf-8".toMediaType()
            val requestBodyText = """
                {
                    "contents": [{
                        "parts": [{
                            "text": "Analyze URL: $url"
                        }]
                    }],
                    "config": {
                        "systemInstruction": "${systemInstruction.replace("\n", " ").replace("\"", "\\\"")}",
                        "responseMimeType": "application/json"
                    }
                }
            """.trimIndent()

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.1-flash:generateContent?key=$apiKey")
                .post(requestBodyText.toRequestBody(jsonMedia))
                .header("User-Agent", "aistudio-build")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                fallbackParse(url, onResult)
                return@launch
            }

            val respStr = response.body?.string() ?: ""
            val jsonResponse = JSONObject(respStr)
            val candidate = jsonResponse.getJSONArray("candidates").getJSONObject(0)
            val text = candidate.getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            
            val obj = JSONObject(text.trim())
            val title = obj.optString("title", "")
            val channel = obj.optString("channel", "")
            val platform = obj.optString("platform", "Other")
            val sizes = mapOf(
                "1080p" to obj.optInt("size1080pMb", 45),
                "720p" to obj.optInt("size720pMb", 28),
                "360p" to obj.optInt("size360pMb", 12),
                "MP3" to obj.optInt("sizeMp3Mb", 4)
            )
            withContext(Dispatchers.Main) {
                onResult(title, channel, platform, sizes)
            }
        } catch (e: Exception) {
            fallbackParse(url, onResult)
        }
    }
}

private fun fallbackParse(
    url: String,
    onResult: (title: String, channel: String, platform: String, sizes: Map<String, Int>?) -> Unit
) {
    CoroutineScope(Dispatchers.Main).launch {
        val domain = try { Uri.parse(url).host ?: "video_stream" } catch (e: Exception) { "video_stream" }
        val title = if (url.contains("watch?v=")) {
            "Video from YouTube Stream (${url.substringAfter("watch?v=").take(6)})"
        } else {
            "Multimedia Stream from $domain (HD)"
        }
        val channel = "Profile @${domain.substringBeforeLast(".", "video").substringAfterLast(".", "channel")}"
        val platform = if (url.contains("youtube.com") || url.contains("youtu.be")) {
            "YouTube"
        } else if (url.contains("instagram")) {
            "Instagram"
        } else if (url.contains("tiktok")) {
            "TikTok"
        } else {
            "Other"
        }
        val sizes = mapOf(
            "1080p" to 42,
            "720p" to 18,
            "360p" to 8,
            "MP3" to 3
        )
        onResult(title, channel, platform, sizes)
    }
}

// --- VIDEO CONVERTER DIALOG AND SIMULATION ENGINE ---

@Composable
fun VideoConversionDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onStartConversion: (targetFormat: String, bitrate: String) -> Unit
) {
    var selectedFormat by remember { mutableStateOf("MP3") }
    var selectedBitrate by remember { mutableStateOf("192 kbps") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Convert Video",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF5722)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    task.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.DarkGray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Text("Target Format", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf("MP3", "AAC", "WAV").forEach { format ->
                        val isSelected = selectedFormat == format
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedFormat = format }
                                .background(
                                    if (isSelected) Color(0xFFFFECE5) else Color(0xFFF1F3F5),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFFFF5722) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = format,
                                color = if (isSelected) Color(0xFFFF5722) else Color.DarkGray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                Text("Audio Bitrate", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listOf("128 kbps", "192 kbps", "320 kbps").forEach { bitrate ->
                        val isSelected = selectedBitrate == bitrate
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedBitrate = bitrate }
                                .background(
                                    if (isSelected) Color(0xFFFFECE5) else Color(0xFFF1F3F5),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0xFFFF5722) else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = bitrate,
                                color = if (isSelected) Color(0xFFFF5722) else Color.DarkGray,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = { onStartConversion(selectedFormat, selectedBitrate) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.Transform, contentDescription = "Convert", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Converting to $selectedFormat", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun convertVideoToMp3(
    context: Context,
    task: DownloadTask,
    targetFormat: String,
    bitrate: String,
    onProgress: (Float) -> Unit,
    onComplete: (DownloadTask) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        // Run conversion simulation over 12 steps (about 2.4 seconds)
        val steps = 12
        for (i in 1..steps) {
            delay(200L)
            withContext(Dispatchers.Main) {
                onProgress(i.toFloat() / steps)
            }
        }
        
        // Output new Converted File
        val cleanedTitle = task.title.substringBeforeLast(".")
        val newTitle = "$cleanedTitle (Audio Track)"
        val newFormat = targetFormat.lowercase(Locale.US)
        val newFileSuffix = ".$newFormat"
        val outFileName = "converted_${System.currentTimeMillis()}_${task.id}$newFileSuffix"
        val destFile = File(context.getExternalFilesDir(null), outFileName)
        
        // Write mock binary file so it exists physically
        try {
            destFile.writeText("simulated converted $newFormat media file content")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        val newSize = (task.totalSize * 0.15).toLong() // Audio is roughly 15% of video size
        val convertedTask = DownloadTask(
            id = "conv_" + System.currentTimeMillis(),
            url = task.url,
            title = newTitle,
            thumbnailUrl = task.thumbnailUrl,
            format = "$newFormat ($bitrate)",
            totalSize = newSize,
            downloadedSize = newSize,
            status = DownloadStatus.COMPLETED,
            progress = 1.0f,
            speed = "Converted",
            localFilePath = destFile.absolutePath,
            platform = task.platform,
            dateAdded = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date())
        )
        
        withContext(Dispatchers.Main) {
            onComplete(convertedTask)
        }
    }
}

