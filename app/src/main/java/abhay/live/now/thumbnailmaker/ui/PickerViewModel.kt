package abhay.live.now.thumbnailmaker.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import abhay.live.now.thumbnailmaker.picker.Config
import abhay.live.now.thumbnailmaker.picker.FramePicker
import abhay.live.now.thumbnailmaker.picker.PickerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PickerViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        data object Idle : UiState()
        data class Processing(val stage: String, val progress: Float) : UiState()
        data class Done(val frames: List<ResultFrame>) : UiState()
        data class Error(val message: String) : UiState()
    }

    data class ResultFrame(
        val bitmap: Bitmap,
        val score: Float,
        val timestampUs: Long
    )

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(
            bitmap, bitmap.width / 2, bitmap.height / 2, true
        )
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var currentJob: Job? = null

    fun pickVideo(uri: Uri) {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            _state.value = UiState.Processing("Initializing", 0f)

            val picker = FramePicker(getApplication(), Config())
            val progressiveResults = mutableListOf<ResultFrame>()

            picker.pick(uri).collect { event ->
                when (event) {
                    is PickerEvent.StageUpdate -> {
                        _state.value = UiState.Processing(event.stage, event.progress)
                    }
                    is PickerEvent.Candidate -> {
                        event.frame.fullResBitmap?.let { bitmap ->
                            progressiveResults.add(
                                ResultFrame(scaleDown(bitmap), event.frame.score, event.frame.timestampUs)
                            )
                            _state.value = UiState.Done(progressiveResults.toList())
                        }
                    }
                    is PickerEvent.Complete -> {
                        // Progressive results already accumulated via Candidate events
                        if (progressiveResults.isEmpty()) {
                            _state.value = UiState.Error("No thumbnails extracted")
                        }
                    }
                    is PickerEvent.Error -> {
                        _state.value = UiState.Error(event.message)
                    }
                }
            }
        }
    }

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    sealed class SaveState {
        data object Idle : SaveState()
        data object Saving : SaveState()
        data class Saved(val count: Int) : SaveState()
        data class Failed(val message: String) : SaveState()
    }

    fun saveAllToGallery() {
        val frames = (_state.value as? UiState.Done)?.frames ?: return
        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            try {
                val count = withContext(Dispatchers.IO) {
                    var saved = 0
                    val resolver = getApplication<Application>().contentResolver
                    for ((index, frame) in frames.withIndex()) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "thumbnail_${index + 1}.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                "${Environment.DIRECTORY_PICTURES}/ThumbnailMaker"
                            )
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                        ) ?: continue
                        resolver.openOutputStream(uri)?.use { out ->
                            frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        saved++
                    }
                    saved
                }
                _saveState.value = SaveState.Saved(count)
            } catch (e: Exception) {
                _saveState.value = SaveState.Failed(e.message ?: "Save failed")
            }
        }
    }

    fun reset() {
        currentJob?.cancel()
        _state.value = UiState.Idle
        _saveState.value = SaveState.Idle
    }
}
