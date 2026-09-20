package abhay.live.now.thumbnailmaker

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import abhay.live.now.thumbnailmaker.ui.theme.ThumbnailMakerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ThumbnailMakerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ThumbnailScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun ThumbnailScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var thumbnails by remember { mutableStateOf<List<File>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Select a video to generate thumbnails") }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessing = true
            statusText = "Extracting frames..."
            scope.launch {
                val files = extractFrames(context, uri)
                thumbnails = files
                isProcessing = false
                statusText = if (files.isNotEmpty()) {
                    "Extracted ${files.size} thumbnails"
                } else {
                    "Failed to extract frames"
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = statusText, style = MaterialTheme.typography.titleMedium)

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { videoPickerLauncher.launch("video/*") },
            enabled = !isProcessing
        ) {
            Text("Pick Video")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isProcessing) {
            CircularProgressIndicator()
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(thumbnails) { file ->
                val bitmap = remember(file) {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

private suspend fun extractFrames(
    context: android.content.Context,
    videoUri: Uri
): List<File> = withContext(Dispatchers.IO) {
    val outputDir = File(context.filesDir, "thumbnails").apply {
        if (exists()) deleteRecursively()
        mkdirs()
    }

    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, videoUri)

        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: return@withContext emptyList()

        val intervalUs = 10_000_000L // 10 seconds in microseconds
        val files = mutableListOf<File>()
        var timeUs = 0L
        var index = 0

        while (timeUs < durationMs * 1000) {
            val bitmap = retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            if (bitmap != null) {
                val file = File(outputDir, "thumb_${index}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                files.add(file)
                index++
            }
            timeUs += intervalUs
        }

        files
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    } finally {
        retriever.release()
    }
}