package com.frameyu.app

import android.os.Bundle
import android.Manifest
import android.os.Build
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.frameyu.app.data.media.MediaUriAccess
import com.frameyu.app.ui.library.LibraryScreen
import com.frameyu.app.ui.library.LibraryViewModel
import com.frameyu.app.ui.library.CandidateBrowserScreen
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.export.StillImageExporter
import com.frameyu.app.export.VideoClipExporter
import com.frameyu.app.export.GifExporter

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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val candidateMedia = selectedCandidateMedia.value
                    if (candidateMedia == null) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onStartAnalysis = libraryViewModel::startVideoAnalysis,
                            onOpenCandidates = { selectedCandidateMedia.value = it },
                            hasMediaReadPermission = hasMediaPermission.value,
                            onRequestMediaReadPermission = ::requestMediaReadPermission,
                            onSelectMedia = {
                                pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            },
                        )
                    } else {
                        CandidateBrowserScreen(
                            media = candidateMedia,
                            database = (application as FrameYuApplication).database,
                            exporter = StillImageExporter(this@MainActivity),
                            clipExporter = VideoClipExporter(this@MainActivity),
                            gifExporter = GifExporter(this@MainActivity),
                            onBack = { selectedCandidateMedia.value = null },
                        )
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
}
