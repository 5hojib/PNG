package com.example

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

// State Representation
data class ResizerUiState(
    val originalUri: Uri? = null,
    val originalFileName: String = "",
    val originalFileSize: Long = 0L,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val originalBitmap: Bitmap? = null,
    
    val isProcessing: Boolean = false,
    
    val resizedFileSize: Long = 0L,
    val resizedWidth: Int = 0,
    val resizedHeight: Int = 0,
    val resizedBitmap: Bitmap? = null,
    val resizedPngBytes: ByteArray? = null,
    
    val errorMessage: String? = null
)

// Main Activity class
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PngResizerScreen()
                }
            }
        }
    }
}

// ViewModel to hold and execute resize mechanics safely on background threads
class ResizerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ResizerUiState())
    val uiState = _uiState.asStateFlow()

    private val _isSharpMode = MutableStateFlow(false)
    val isSharpMode = _isSharpMode.asStateFlow()

    private var originalBytes: ByteArray? = null

    fun setSharpMode(value: Boolean) {
        _isSharpMode.value = value
        val currentBitmap = _uiState.value.originalBitmap
        if (currentBitmap != null) {
            processResizing(currentBitmap, value)
        }
    }

    fun onImageSelected(context: Context, uri: Uri) {
        _uiState.update {
            it.copy(
                isProcessing = true,
                errorMessage = null,
                resizedBitmap = null,
                resizedPngBytes = null
            )
        }

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolver = context.contentResolver
                val fileName = getFileNameFromUri(context, uri)
                val fileSize = getFileSizeFromUri(context, uri)

                resolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    originalBytes = bytes

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap == null) {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                errorMessage = "Unsupported format or file corrupted. Please choose a valid PNG image."
                            )
                        }
                        return@launch
                    }

                    _uiState.update {
                        it.copy(
                            originalUri = uri,
                            originalFileName = fileName,
                            originalFileSize = fileSize,
                            originalWidth = bitmap.width,
                            originalHeight = bitmap.height,
                            originalBitmap = bitmap,
                            isProcessing = false
                        )
                    }

                    // Perform resolution reduction automatically upon selection
                    processResizing(bitmap, _isSharpMode.value)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Error opening image: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun processResizing(sourceBitmap: Bitmap, isSharp: Boolean) {
        _uiState.update { it.copy(isProcessing = true, errorMessage = null) }

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val srcWidth = sourceBitmap.width
                val srcHeight = sourceBitmap.height

                val maxSide = maxOf(srcWidth, srcHeight)
                if (maxSide <= 0) {
                    _uiState.update {
                        it.copy(
                            isProcessing = false,
                            errorMessage = "Image dimension computation failed."
                        )
                    }
                    return@launch
                }

                // Mathematical scaling constraint: Maximum side length is strictly scaled to 100px
                val scale = 100f / maxSide
                val targetWidth = (srcWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (srcHeight * scale).roundToInt().coerceAtLeast(1)

                // Scaling execution
                // filter = true (Bilinear) ensures high visual smoothness and clarity
                // filter = false (Nearest-Neighbor) maintains sharp crisp grid layouts (perfect for pixel-art and sprites)
                val resized = if (isSharp) {
                    Bitmap.createScaledBitmap(sourceBitmap, targetWidth, targetHeight, false)
                } else {
                    Bitmap.createScaledBitmap(sourceBitmap, targetWidth, targetHeight, true)
                }

                // Compress back to PNG losslessly (the quality argument of 100 is supplied but normally ignored in standard PNG format compression)
                val outputStream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val pngBytes = outputStream.toByteArray()

                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        resizedWidth = targetWidth,
                        resizedHeight = targetHeight,
                        resizedBitmap = resized,
                        resizedPngBytes = pngBytes,
                        resizedFileSize = pngBytes.size.toLong()
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = "Resolution compression failed: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun saveResizedImage(context: Context, onResult: (Uri?) -> Unit) {
        val state = _uiState.value
        val bytes = state.resizedPngBytes
        if (bytes == null) {
            onResult(null)
            return
        }

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val uri = savePngToGallery(context, bytes, state.originalFileName)
            withContext(Dispatchers.Main) {
                onResult(uri)
            }
        }
    }

    fun shareResizedImage(context: Context, onResult: (Uri?) -> Unit) {
        val state = _uiState.value
        val bytes = state.resizedPngBytes
        if (bytes == null) {
            onResult(null)
            return
        }

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = File(context.cacheDir, "shared_images").apply { mkdirs() }
                cacheDir.listFiles()?.forEach { it.delete() } // clear older temp items

                val cleanName = if (state.originalFileName.endsWith(".png", ignoreCase = true)) {
                    state.originalFileName.substring(0, state.originalFileName.length - 4) + "_100px.png"
                } else {
                    "image_100px.png"
                }

                val file = File(cacheDir, cleanName)
                FileOutputStream(file).use { out ->
                    out.write(bytes)
                }

                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                withContext(Dispatchers.Main) {
                    onResult(shareUri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
        }
    }

    fun reset() {
        originalBytes = null
        _uiState.value = ResizerUiState()
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "unnamed.png"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) name = cursor.getString(idx)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return name
    }

    private fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx != -1) size = cursor.getLong(idx)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (size == 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    size = input.available().toLong()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return size
    }
}

// MediaStore helper to store png losslessly
fun savePngToGallery(context: Context, pngBytes: ByteArray, originalName: String): Uri? {
    val baseName = if (originalName.contains(".")) {
        originalName.substringBeforeLast(".")
    } else {
        originalName
    }
    val cleanName = "${baseName}_100px.png"

    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/100px_PNG_Resizer")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    var imageUri: Uri? = null
    try {
        imageUri = resolver.insert(collectionUri, contentValues)
        if (imageUri != null) {
            resolver.openOutputStream(imageUri)?.use { outputStream ->
                outputStream.write(pngBytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        if (imageUri != null) {
            resolver.delete(imageUri, null, null)
        }
        imageUri = null
    }
    return imageUri
}

// Helper to format bytes cleanly
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 2)
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

// Custom Draw checkerboard background for grid evaluation showing translucent layers
fun Modifier.drawCheckerboard(color1: Color, color2: Color, sizeDp: Dp = 8.dp): Modifier = drawBehind {
    val sizePx = sizeDp.toPx()
    val cols = (size.width / sizePx).toInt() + 1
    val rows = (size.height / sizePx).toInt() + 1
    for (c in 0 until cols) {
        for (r in 0 until rows) {
            val fill = if ((c + r) % 2 == 0) color1 else color2
            drawRect(
                color = fill,
                topLeft = Offset(c * sizePx, r * sizePx),
                size = Size(sizePx, sizePx)
            )
        }
    }
}

// Custom dashes decoration modifier
fun Modifier.drawDashedBorder(color: Color, strokeWidth: Dp = 1.dp, dashLength: Dp = 6.dp): Modifier = drawBehind {
    val strokeWidthPx = strokeWidth.toPx()
    val dashLengthPx = dashLength.toPx()
    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLengthPx, dashLengthPx), 0f)
    drawRect(
        color = color,
        style = Stroke(width = strokeWidthPx, pathEffect = pathEffect)
    )
}

// Color getters for responsive checkerboard backgrounds
@Composable
fun getCheckerboardColors(): Pair<Color, Color> {
    return if (isSystemInDarkTheme()) {
        Color(0xFF334155) to Color(0xFF1E293B) // slate-700 and slate-800
    } else {
        Color(0xFFF1F5F9) to Color(0xFFD8E2EF) // light gray/slate accents
    }
}

// Main layout screen Composable
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PngResizerScreen(viewModel: ResizerViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSharpMode by viewModel.isSharpMode.collectAsStateWithLifecycle()
    
    val checkerColors = getCheckerboardColors()

    // File Selector launcher targeting image/png
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.onImageSelected(context, uri)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "Lossless PNG Optimizer v1.0", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu info",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = "Shrinkr",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            val msg = "Resolution Limit: 100px highest side\nRender engine: ${if (isSharpMode) "Sharp (Retro Pixel)" else "Smooth (Bilinear)"}"
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings info",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            // Bold Typography Hero Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "CONSTRAINT",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.offset(y = (-6).dp)
                ) {
                    Text(
                        text = "100",
                        fontSize = 88.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-4).sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 88.sp
                    )
                    Text(
                        text = "PX",
                        fontSize = 88.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-4).sp,
                        color = MaterialTheme.colorScheme.primary,
                        lineHeight = 88.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "Highest side limited. Original ratio maintained. Visual clarity preserved.",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    lineHeight = 22.sp,
                    modifier = Modifier.widthIn(max = 260.dp)
                )
            }

            // Image Picker selector block (Empty state or selector triggers)
            if (uiState.originalBitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.15f)
                        .clip(RoundedCornerShape(32.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        .drawDashedBorder(MaterialTheme.colorScheme.primary, 2.dp, 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current
                        ) {
                            pickImageLauncher.launch("image/png")
                        }
                        .testTag("dashed_picker_box"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = "Upload Icon",
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Pick Original PNG",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Drag & drop or tap to browse",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Info Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Chip 1
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                            .background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Lossless Quality",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))

                    // Chip 2
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                            .background(Color.White)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Fixed Aspect",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            } else {
                // Display Reset/Choose Another Option Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Selected Workspace",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = { viewModel.reset() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("reset_button")
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }

                // Workspace UI Layout
                OriginalMetadataCard(uiState = uiState, checkerColors = checkerColors)

                Spacer(modifier = Modifier.height(4.dp))

                // Advanced Controls: smooth bilinear vs sharp pixelated
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Visual Rendering Style",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                                .padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (!isSharpMode) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .clickable { viewModel.setSharpMode(false) }
                                    .testTag("toggle_smooth"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.BlurOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (!isSharpMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Smooth (Bilinear)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!isSharpMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (isSharpMode) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .clickable { viewModel.setSharpMode(true) }
                                    .testTag("toggle_sharp"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GridOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (isSharpMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Sharp (Retro Pixel)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSharpMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Text(
                            text = if (isSharpMode) "Perfect for sprites, small pixel art, or pixelated icons to preserve crisp straight edge lines." 
                                   else "Best for templates, UI logos, and standard photos/drawings to scale edges smoothly with bilinear rendering.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Error Display banner
            uiState.errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error icon",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Processing Indicator
            if (uiState.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Applying resolution scaling to 100px...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Scaled Output Presentation area
            AnimatedVisibility(
                visible = uiState.resizedBitmap != null && !uiState.isProcessing,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val resizedBitmap = uiState.resizedBitmap
                if (resizedBitmap != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Optimized Output Details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.5).sp,
                            color = MaterialTheme.colorScheme.primary
                        )

                        ResizedOutputCard(
                            uiState = uiState,
                            resizedBitmap = resizedBitmap,
                            isSharp = isSharpMode,
                            checkerColors = checkerColors
                        )

                        // Export actions row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.saveResizedImage(context) { savedUri ->
                                        if (savedUri != null) {
                                            Toast.makeText(
                                                context, 
                                                "Successfully saved PNG to Pictures/100px_PNG_Resizer!", 
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            Toast.makeText(
                                                context, 
                                                "Failed to save image to storage.", 
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(56.dp)
                                    .testTag("save_gallery_button"),
                                shape = RoundedCornerShape(28.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Save, contentDescription = "Save Option")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save Image", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.shareResizedImage(context) { shareUri ->
                                        if (shareUri != null) {
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Share Resized PNG"))
                                        } else {
                                            Toast.makeText(context, "Could not register file for sharing.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .testTag("share_button"),
                                shape = RoundedCornerShape(28.dp),
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = "Share Option")
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Share", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            // Quick trigger button if no image selected yet, styled as main action trigger
            if (uiState.originalBitmap == null) {
                Button(
                    onClick = { pickImageLauncher.launch("image/png") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("pick_initial_image_button"),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(imageVector = Icons.Default.AddPhotoAlternate, contentDescription = "Pick Image Flag")
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Pick Original PNG", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

// Block displaying details of original PNG
@Composable
fun OriginalMetadataCard(uiState: ResizerUiState, checkerColors: Pair<Color, Color>) {
    val bitmap = uiState.originalBitmap ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular thumbnail boundary with custom checkerboard
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .drawCheckerboard(checkerColors.first, checkerColors.second),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Original Thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                
                Spacer(modifier = Modifier.width(14.dp))
                
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = uiState.originalFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Original Dimensions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${uiState.originalWidth} x ${uiState.originalHeight} pixels",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Stats breakdown
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("File Format", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("PNG (lossless)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Digital File Size", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatBytes(uiState.originalFileSize), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Comprehensive zoomed checkerboard view block + metrics calculation card
@Composable
fun ResizedOutputCard(
    uiState: ResizerUiState,
    resizedBitmap: Bitmap,
    isSharp: Boolean,
    checkerColors: Pair<Color, Color>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            // Scaled Zoom View
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Checkerboard Detail Zoom (Scaled)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Transparency channels mapped on background checker overlay",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                // Custom viewport demonstrating real canvas drawing with chosen FilterQuality option
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .drawCheckerboard(checkerColors.first, checkerColors.second)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    ) {
                        drawImage(
                            image = resizedBitmap.asImageBitmap(),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            filterQuality = if (isSharp) FilterQuality.None else FilterQuality.Low
                        )
                    }
                }
            }

            // Double layouts showing stats comparison side-by-side
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sizing labels and specs
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Original: ${uiState.originalWidth}×${uiState.originalHeight}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Output: ${uiState.resizedWidth}×${uiState.resizedHeight}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Highest side: exactement 100px",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                }

                // File reductions stats + green percentage pill badge
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val savings = if (uiState.originalFileSize > 0) {
                        ((uiState.originalFileSize - uiState.resizedFileSize).toFloat() * 100f / uiState.originalFileSize.toFloat())
                    } else 0f

                    Text(
                        text = "Reduced from ${formatBytes(uiState.originalFileSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = formatBytes(uiState.resizedFileSize),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (savings > 0) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF22C55E)) // emerald - green badge success
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = String.format("-%.1f%% Save", savings),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Centered small 1:1 footprint checker row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Actual physical scale bounds (100px canvas preview):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .drawCheckerboard(checkerColors.first, checkerColors.second)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = resizedBitmap.asImageBitmap(),
                        contentDescription = "Scale Footprint",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}
