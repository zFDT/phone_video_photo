package com.frameyu.app

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.frameyu.app.data.media.MediaUriAccess
import com.frameyu.app.ui.library.LibraryScreen
import com.frameyu.app.ui.library.LibraryViewModel
import com.frameyu.app.ui.library.CandidateBrowserScreen
import com.frameyu.app.ui.library.CandidateDetailScreen
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.export.StillImageExporter
import com.frameyu.app.export.VideoClipExporter
import com.frameyu.app.export.GifExporter
import com.frameyu.app.export.MotionPhotoExporter
import com.frameyu.app.organize.DuplicateDetector
import com.frameyu.app.organize.ContentHashDuplicateDetector
import com.frameyu.app.organize.ContentHashReader
import com.frameyu.app.organize.OrganizerRepository
import com.frameyu.app.ui.organize.OrganizerScreen
import com.frameyu.app.settings.SettingsSnapshot
import com.frameyu.app.settings.SettingsStore
import com.frameyu.app.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val libraryViewModel by viewModels<LibraryViewModel> {
        val frameYuApplication = application as FrameYuApplication
        val repository = frameYuApplication.mediaStoreRepository
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(
                    repository = repository,
                    analysisTaskDao = frameYuApplication.database.analysisTaskDao(),
                    analysisCandidateDao = frameYuApplication.database.analysisCandidateDao(),
                    workScheduler = frameYuApplication.videoAnalysisWorkScheduler,
                ) as T
        }
    }
    private val mediaUriAccess by lazy { MediaUriAccess(this) }
    private var hasMediaPermission = mutableStateOf(false)
    private var selectedCandidateMedia = mutableStateOf<MediaAssetEntity?>(null)
    private var selectedCandidate = mutableStateOf<AnalysisCandidateEntity?>(null)
    private var showOrganizer = mutableStateOf(false)
    private var showSettings = mutableStateOf(false)
    private var settingsSnapshot = mutableStateOf(SettingsSnapshot())
    private val settingsStore by lazy {
        SettingsStore(getSharedPreferences(SettingsStore.PREFERENCES_NAME, MODE_PRIVATE))
    }
    private val organizerRepository by lazy {
        OrganizerRepository(
            detector = DuplicateDetector(),
            contentHashDetector = ContentHashDuplicateDetector(contentHashReader()),
            deleter = ::moveToSystemTrash,
        )
    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasMediaPermission.value = mediaUriAccess.hasMediaReadPermission() }
    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(mediaUriAccess::persistPhotoPickerReadPermission) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hasMediaPermission.value = mediaUriAccess.hasMediaReadPermission()
        settingsSnapshot.value = settingsStore.read()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val candidateMedia = selectedCandidateMedia.value
                    if (candidateMedia == null && showOrganizer.value) {
                        OrganizerScreen(
                            database = (application as FrameYuApplication).database,
                            repository = organizerRepository,
                            onExactDuplicateScan = { assets ->
                                withContext(Dispatchers.IO) {
                                    organizerRepository.exactDuplicateCandidates(assets)
                                }
                            },
                            onBack = { showOrganizer.value = false },
                        )
                    } else if (candidateMedia == null && showSettings.value) {
                        SettingsScreen(
                            settings = settingsSnapshot.value,
                            onSettingsChanged = { settings ->
                                settingsSnapshot.value = settings
                                settingsStore.save(settings)
                            },
                            onBack = { showSettings.value = false },
                        )
                    } else if (candidateMedia == null) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onStartAnalysis = libraryViewModel::startVideoAnalysis,
                            onStartBatchAnalysis = libraryViewModel::startBatchVideoAnalysis,
                            onCancelBatchAnalysis = libraryViewModel::cancelBatchVideoAnalysis,
                            onOpenCandidates = { selectedCandidateMedia.value = it },
                            onOpenOrganizer = { showOrganizer.value = true },
                            onOpenSettings = { showSettings.value = true },
                            hasMediaReadPermission = hasMediaPermission.value,
                            onRequestMediaReadPermission = ::requestMediaReadPermission,
                            onSelectMedia = {
                                pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                        )
                    } else {
                        val candidate = selectedCandidate.value
                        if (candidate == null) {
                            CandidateBrowserScreen(
                                media = candidateMedia,
                                database = (application as FrameYuApplication).database,
                                exporter = StillImageExporter(this@MainActivity),
                                clipExporter = VideoClipExporter(this@MainActivity),
                                gifExporter = GifExporter(this@MainActivity),
                                motionPhotoExporter = MotionPhotoExporter(
                                    context = this@MainActivity,
                                    stillImageExporter = StillImageExporter(this@MainActivity),
                                    clipExporter = VideoClipExporter(this@MainActivity),
                                ),
                                onOpenDetail = { selectedCandidate.value = it },
                                onBack = {
                                    selectedCandidate.value = null
                                    selectedCandidateMedia.value = null
                                },
                            )
                        } else {
                            CandidateDetailScreen(
                                media = candidateMedia,
                                candidate = candidate,
                                onSetCover = {
                                    lifecycleScope.launch {
                                        (application as FrameYuApplication).database.analysisCandidateDao()
                                            .setCover(candidateMedia.id, candidate.id)
                                    }
                                },
                                onBack = { selectedCandidate.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestMediaReadPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    private suspend fun moveToSystemTrash(uris: List<Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_TRASHED, 1)
                    },
                    null,
                    null,
                )
            } else {
                contentResolver.delete(uri, null, null)
            }
        }
    }

    /** 以分块流读取媒体，避免长视频在完全重复扫描时一次性进入内存。 */
    private fun contentHashReader(): ContentHashReader = ContentHashReader { uri ->
        sequence<ByteArray> {
            val input = contentResolver.openInputStream(Uri.parse(uri)) ?: error("无法打开媒体：$uri")
            input.use { stream ->
                val buffer = ByteArray(HASH_BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer)
                    if (count <= 0) break
                    yield(buffer.copyOf(count))
                }
            }
        }.asIterable()
    }

    private companion object {
        const val HASH_BUFFER_SIZE = 1024 * 1024
    }
}
