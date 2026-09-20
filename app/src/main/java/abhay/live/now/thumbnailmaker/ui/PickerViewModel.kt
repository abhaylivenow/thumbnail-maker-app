package abhay.live.now.thumbnailmaker.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
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
import abhay.live.now.thumbnailmaker.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class PickerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val BASE_URL = "https://prod-thumbnail-maker.up.railway.app"
    }

    sealed class UiState {
        data object Idle : UiState()
        data class Processing(val stage: String, val progress: Float) : UiState()
        data class Done(val frames: List<ResultFrame>) : UiState()
        data class Uploading(val message: String) : UiState()
        data class ServerResult(
            val thumbnail: Bitmap,
            val frameChoice: Int,
            val text: String,
            val style: String,
            val color: String
        ) : UiState()
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

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(
                    okhttp3.logging.HttpLoggingInterceptor().apply {
                        level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
                    }
                )
            }
        }
        .build()

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

    fun sendToServer() {
        val frames = (_state.value as? UiState.Done)?.frames ?: return
        viewModelScope.launch {
            _state.value = UiState.Uploading("Sending frames to server...")
            try {
                val result = withContext(Dispatchers.IO) {
                    val multipartBuilder = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)

                    for ((index, frame) in frames.withIndex()) {
                        val baos = ByteArrayOutputStream()
                        frame.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                        val bytes = baos.toByteArray()
                        multipartBuilder.addFormDataPart(
                            "frames",
                            "thumbnail_${index + 1}.jpg",
                            bytes.toRequestBody("image/jpeg".toMediaType())
                        )
                    }

                    val request = Request.Builder()
                        .url("$BASE_URL/jobs")
                        .post(multipartBuilder.build())
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val body = response.body?.string()
                        ?: throw Exception("Empty response from server")

                    if (!response.isSuccessful) {
                        throw Exception("Server error ${response.code}: $body")
                    }

                    val json = JSONObject(body)
                    val resultObj = json.getJSONObject("result")

                    val thumbnailBase64 = resultObj.getString("thumbnailBase64")
                    val imageBytes = Base64.decode(thumbnailBase64, Base64.DEFAULT)
                    val thumbnail = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        ?: throw Exception("Failed to decode server thumbnail")

                    ServerResponse(
                        thumbnail = thumbnail,
                        frameChoice = resultObj.getInt("frameChoice"),
                        text = resultObj.optString("text", ""),
                        style = resultObj.optString("style", ""),
                        color = resultObj.optString("color", "")
                    )
                }

                _state.value = UiState.ServerResult(
                    thumbnail = result.thumbnail,
                    frameChoice = result.frameChoice,
                    text = result.text,
                    style = result.style,
                    color = result.color
                )
            } catch (e: Exception) {
                _state.value = UiState.Error("Upload failed: ${e.message}")
            }
        }
    }

    private data class ServerResponse(
        val thumbnail: Bitmap,
        val frameChoice: Int,
        val text: String,
        val style: String,
        val color: String
    )

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
