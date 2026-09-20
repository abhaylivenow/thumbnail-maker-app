package abhay.live.now.thumbnailmaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import abhay.live.now.thumbnailmaker.ui.PickerViewModel
import abhay.live.now.thumbnailmaker.ui.theme.ThumbnailMakerTheme

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
fun ThumbnailScreen(
    modifier: Modifier = Modifier,
    viewModel: PickerViewModel = viewModel()
) {
    val uiState by viewModel.state.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.pickVideo(uri)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status text
        when (val state = uiState) {
            is PickerViewModel.UiState.Idle -> {
                Text(
                    text = "Select a video to generate thumbnails",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            is PickerViewModel.UiState.Processing -> {
                Text(
                    text = state.stage,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (state.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
            is PickerViewModel.UiState.Done -> {
                Text(
                    text = "Extracted ${state.frames.size} thumbnails",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            is PickerViewModel.UiState.Error -> {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Buttons
        val isProcessing = uiState is PickerViewModel.UiState.Processing
        Button(
            onClick = { videoPickerLauncher.launch("video/*") },
            enabled = !isProcessing
        ) {
            Text("Pick Video")
        }

        if (uiState is PickerViewModel.UiState.Done) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveAllToGallery() },
                enabled = saveState !is PickerViewModel.SaveState.Saving
            ) {
                Text(
                    when (saveState) {
                        is PickerViewModel.SaveState.Saving -> "Saving..."
                        is PickerViewModel.SaveState.Saved ->
                            "Saved ${(saveState as PickerViewModel.SaveState.Saved).count} to Gallery"
                        else -> "Download All"
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.reset() }) {
                Text("Reset")
            }
        }

        if (uiState is PickerViewModel.UiState.Error) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.reset() }) {
                Text("Reset")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Results grid
        val frames = (uiState as? PickerViewModel.UiState.Done)?.frames ?: emptyList()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(frames, key = { it.timestampUs }) { frame ->
                Box {
                    Image(
                        bitmap = frame.bitmap.asImageBitmap(),
                        contentDescription = "Thumbnail at ${frame.timestampUs / 1_000_000}s",
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        text = "%.2f".format(frame.score),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}
