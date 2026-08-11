package com.frameyu.app

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
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
import com.frameyu.app.analysis.batch.BatchAnalysisIntents
import com.frameyu.app.ui.library.LibraryScreen
import com.frameyu.app.ui.library.LibraryViewModel
import com.frameyu.app.ui.library.CandidateBrowserScreen
import com.frameyu.app.ui.library.CandidateDetailScreen
import com.frameyu.app.ui.analysis.BatchAnalysisScreen
import com.frameyu.app.data.db.MediaAssetEntity
import com.frameyu.app.data.db.AnalysisCandidateEntity
import com.frameyu.app.export.StillImageExporter
import com.frameyu.app.export.VideoClipExporter
import com.frameyu.app.export.GifExporter
import com.frameyu.app.export.MotionPhotoExporter
import com.frameyu.app.organize.AndroidVisualHashReader
import com.frameyu.app.organize.AndroidLiveMediaMetadataReader
import com.frameyu.app.organize.AndroidPendingMediaAuthorizationStore
import com.frameyu.app.organize.ContentSha256Reader
import com.frameyu.app.organize.DeletionSafetyValidator
import com.frameyu.app.organize.DuplicateDetector
import com.frameyu.app.organize.ContentHashDuplicateDetector
import com.frameyu.app.organize.ContentHashReader
import com.frameyu.app.organize.OrganizerRepository
import com.frameyu.app.organize.MediaDeletionResult
import com.frameyu.app.organize.MediaDeletionFailure
import com.frameyu.app.organize.MediaDeleteAttempt
import com.frameyu.app.organize.MediaAuthorizationGate
import com.frameyu.app.organize.MediaAuthorizationAction
import com.frameyu.app.organize.MediaAuthorizationStateReducer
import com.frameyu.app.organize.PendingMediaAuthorization
import com.frameyu.app.organize.PendingMediaAuthorizationPhase
import com.frameyu.app.organize.PendingMediaAuthorizationType
import com.frameyu.app.organize.executeMediaDeletion
import com.frameyu.app.organize.executeRecoverableMediaDeletion
import com.frameyu.app.organize.executeTrashRequestDeletion
import com.frameyu.app.organize.chunkMediaAuthorizationUris
import com.frameyu.app.organize.sha256HexCancellable
import com.frameyu.app.organize.SimilarMediaDuplicateDetector
import com.frameyu.app.organize.similarPhotoCandidates
import com.frameyu.app.ui.organize.OrganizerScreen
import com.frameyu.app.settings.SettingsSnapshot
import com.frameyu.app.settings.SettingsStore
import com.frameyu.app.ui.settings.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.UUID

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
                    workScheduler = frameYuApplication.videoAnalysisWorkScheduler,
                ) as T
        }
    }
    private val mediaUriAccess by lazy { MediaUriAccess(this) }
    private var hasMediaPermission = mutableStateOf(false)
    private var selectedCandidateMedia = mutableStateOf<MediaAssetEntity?>(null)
    private var selectedCandidate = mutableStateOf<AnalysisCandidateEntity?>(null)
    private var returnToOrganizerAfterCandidates = mutableStateOf(false)
    private var selectedBatchId = mutableStateOf<Long?>(null)
    private var batchAnalysisStarting = mutableStateOf(false)
    private var batchAnalysisMessage = mutableStateOf<String?>(null)
    private var showOrganizer = mutableStateOf(false)
    private var showSettings = mutableStateOf(false)
    private var settingsSnapshot = mutableStateOf(SettingsSnapshot())
    private val mediaAuthorizationGate = MediaAuthorizationGate()
    private val mediaAuthorizationReducer = MediaAuthorizationStateReducer()
    private val mediaAuthorizationLock = Any()
    private var pendingMediaAuthorization: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private var durablePendingMediaAuthorization: PendingMediaAuthorization? = null
    private var mediaAuthorizationCallbackRequestId: String? = null
    private val mediaAuthorizationStore by lazy {
        AndroidPendingMediaAuthorizationStore(this)
    }
    private val settingsStore by lazy {
        SettingsStore(getSharedPreferences(SettingsStore.PREFERENCES_NAME, MODE_PRIVATE))
    }
    private val organizerRepository by lazy {
        val hashReader = contentHashReader()
        OrganizerRepository(
            detector = DuplicateDetector(),
            contentHashDetector = ContentHashDuplicateDetector(hashReader),
            deleter = ::moveToSystemTrash,
            deletionSafetyValidator = DeletionSafetyValidator(
                metadataReader = AndroidLiveMediaMetadataReader(contentResolver),
                sha256Reader = ContentSha256Reader { uri ->
                    hashReader.sha256(uri)
                },
            ),
        )
    }
    private val similarMediaDuplicateDetector by lazy {
        SimilarMediaDuplicateDetector(AndroidVisualHashReader(contentResolver))
    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasMediaPermission.value = mediaUriAccess.hasMediaReadPermission() }
    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(mediaUriAccess::persistPhotoPickerReadPermission) }
    private val mediaAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        handleMediaAuthorizationResult(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openBatchFromIntent(intent)
        restorePendingMediaAuthorization(savedInstanceState)
        recoverPendingMediaAuthorization()
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
                            onSimilarDuplicateScan = { assets ->
                                withContext(Dispatchers.IO) {
                                    similarMediaDuplicateDetector.detect(similarPhotoCandidates(assets))
                                }
                            },
                            onOpenVideoCandidates = {
                                showOrganizer.value = false
                                returnToOrganizerAfterCandidates.value = true
                                selectedCandidateMedia.value = it
                            },
                            onBack = { showOrganizer.value = false },
                        )
                    } else if (candidateMedia == null && selectedBatchId.value != null) {
                        val batchId = checkNotNull(selectedBatchId.value)
                        val frameYuApplication = application as FrameYuApplication
                        BatchAnalysisScreen(
                            batchId = batchId,
                            database = frameYuApplication.database,
                            scheduler = frameYuApplication.batchAnalysisWorkScheduler,
                            onOpenOrganizer = {
                                selectedBatchId.value = null
                                showOrganizer.value = true
                            },
                            onBack = { selectedBatchId.value = null },
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
                            onOpenCandidates = {
                                returnToOrganizerAfterCandidates.value = false
                                selectedCandidateMedia.value = it
                            },
                            onOpenDeepAnalysis = ::openDeepAnalysis,
                            deepAnalysisStarting = batchAnalysisStarting.value,
                            deepAnalysisMessage = batchAnalysisMessage.value,
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
                                    if (returnToOrganizerAfterCandidates.value) {
                                        returnToOrganizerAfterCandidates.value = false
                                        showOrganizer.value = true
                                    }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openBatchFromIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        AndroidPendingMediaAuthorizationStore.toBundle(mediaAuthorizationCallbackRequestId)?.let {
            outState.putBundle(STATE_PENDING_MEDIA_AUTHORIZATION, it)
        }
        super.onSaveInstanceState(outState)
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

    private fun openBatchFromIntent(intent: Intent?) {
        val batchId = intent?.let(BatchAnalysisIntents::openBatchId) ?: return
        selectedBatchId.value = batchId
        showOrganizer.value = false
        showSettings.value = false
        selectedCandidateMedia.value = null
        selectedCandidate.value = null
        returnToOrganizerAfterCandidates.value = false
    }

    private fun openDeepAnalysis() {
        if (batchAnalysisStarting.value) return
        batchAnalysisStarting.value = true
        batchAnalysisMessage.value = null
        lifecycleScope.launch {
            try {
                val batchId = (application as FrameYuApplication)
                    .batchAnalysisCoordinator
                    .openOrCreateBatch()
                selectedBatchId.value = batchId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                batchAnalysisMessage.value =
                    "批量深度分析启动失败：${error.message ?: "未知错误"}"
            } finally {
                batchAnalysisStarting.value = false
            }
        }
    }

    private suspend fun moveToSystemTrash(uriStrings: List<String>): MediaDeletionResult {
        val distinctUris = uriStrings.distinct()
        if (distinctUris.isEmpty()) return MediaDeletionResult(emptyList(), emptyList())
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> moveToSystemTrashRequests(distinctUris)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> executeRecoverableMediaDeletion(
                uris = distinctUris,
                deleteOne = { uriString ->
                    try {
                        val deleted = withContext(Dispatchers.IO) {
                            contentResolver.delete(Uri.parse(uriString), null, null) > 0
                        }
                        if (deleted) MediaDeleteAttempt.Deleted else MediaDeleteAttempt.NotHandled
                    } catch (securityError: RecoverableSecurityException) {
                        val request = PendingMediaAuthorization(
                            requestId = newMediaAuthorizationRequestId(),
                            createdAtMillis = System.currentTimeMillis(),
                            requestType = PendingMediaAuthorizationType.RECOVERABLE_DELETE,
                            uris = listOf(uriString),
                            currentUri = uriString,
                        )
                        MediaDeleteAttempt.RequiresAuthorization {
                            awaitMediaAuthorization(
                                securityError.userAction.actionIntent.intentSender,
                                request,
                            )
                        }
                    }
                },
                removeSucceededFromIndex = ::removeMediaFromIndex,
                onAuthorizedRetrySettled = ::completeRecoverableDeleteAuthorization,
            )
            else -> withContext(Dispatchers.IO) {
                executeMediaDeletion(
                    uris = distinctUris,
                    deleteOne = { uriString ->
                        contentResolver.delete(Uri.parse(uriString), null, null) > 0
                    },
                    removeSucceededFromIndex = ::removeMediaFromIndex,
                )
            }
        }
    }

    /**
     * 系统回收站面板每次最多携带 100 个 Uri。Activity 存活时逐块处理；若进程中断，
     * 持久层只保留当前块，未发起的块仍留在 Room，用户可重新选择，避免误判为已处理。
     */
    private suspend fun moveToSystemTrashRequests(
        uriStrings: List<String>,
    ): MediaDeletionResult {
        val chunks = chunkMediaAuthorizationUris(uriStrings)
        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<MediaDeletionFailure>()
        chunks.forEachIndexed { index, chunk ->
            val result = moveToSystemTrashRequest(chunk)
            succeeded += result.succeededUris
            failures += result.failures
            if (result.failures.isNotEmpty()) {
                chunks.drop(index + 1).flatten().forEach { uri ->
                    failures += MediaDeletionFailure(uri, "前一批系统授权未完成，本项尚未处理")
                }
                return MediaDeletionResult(succeeded.toList(), failures.toList())
            }
        }
        return MediaDeletionResult(succeeded.toList(), failures.toList())
    }

    private suspend fun moveToSystemTrashRequest(
        uriStrings: List<String>,
    ): MediaDeletionResult {
        val request = PendingMediaAuthorization(
            requestId = newMediaAuthorizationRequestId(),
            createdAtMillis = System.currentTimeMillis(),
            requestType = PendingMediaAuthorizationType.TRASH,
            uris = uriStrings,
            currentUri = null,
        )
        return executeTrashRequestDeletion(
            uris = uriStrings,
            requestTrash = { requestedUris ->
                val pendingIntent = MediaStore.createTrashRequest(
                    contentResolver,
                    requestedUris.map(Uri::parse),
                    true,
                )
                awaitMediaAuthorization(pendingIntent.intentSender, request)
            },
            removeSucceededFromIndex = { succeededUris ->
                removeMediaFromIndex(succeededUris)
                completeMatchingMediaAuthorization(request)
            },
        )
    }

    private suspend fun awaitMediaAuthorization(
        intentSender: IntentSender,
        request: PendingMediaAuthorization,
    ): Boolean =
        mediaAuthorizationGate.run {
            reconcilePendingBeforeNewRequest()
            val begin = mediaAuthorizationReducer.begin(
                current = durablePendingMediaAuthorization,
                request = request,
            )
            check(begin.action != MediaAuthorizationAction.RejectNewRequest) {
                "已有媒体授权请求尚未完成"
            }
            updateDurableMediaAuthorization(begin.pending)
            suspendCancellableCoroutine { continuation ->
                synchronized(mediaAuthorizationLock) {
                    check(pendingMediaAuthorization == null) { "已有媒体授权请求正在等待结果" }
                    pendingMediaAuthorization = continuation
                }
                continuation.invokeOnCancellation {
                    synchronized(mediaAuthorizationLock) {
                        if (pendingMediaAuthorization === continuation) {
                            pendingMediaAuthorization = null
                        }
                    }
                }
                try {
                    mediaAuthorizationCallbackRequestId = request.requestId
                    mediaAuthorizationLauncher.launch(
                        IntentSenderRequest.Builder(intentSender).build(),
                    )
                } catch (error: Exception) {
                    val shouldResume = synchronized(mediaAuthorizationLock) {
                        if (pendingMediaAuthorization === continuation) {
                            pendingMediaAuthorization = null
                            true
                        } else {
                            false
                        }
                    }
                    if (mediaAuthorizationCallbackRequestId == request.requestId) {
                        mediaAuthorizationCallbackRequestId = null
                    }
                    updateDurableMediaAuthorization(
                        mediaAuthorizationReducer.launchFailed(
                            current = durablePendingMediaAuthorization,
                            request = request,
                        ),
                    )
                    if (shouldResume && continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }

    private fun handleMediaAuthorizationResult(granted: Boolean) {
        val callbackRequestId = mediaAuthorizationCallbackRequestId
        mediaAuthorizationCallbackRequestId = null
        val continuation = synchronized(mediaAuthorizationLock) {
            pendingMediaAuthorization.also { pendingMediaAuthorization = null }
        }
        val continuationPresent = continuation?.isActive == true
        val transition = mediaAuthorizationReducer.onActivityResult(
            current = durablePendingMediaAuthorization,
            callbackRequestId = callbackRequestId,
            granted = granted,
            continuationPresent = continuationPresent,
        )
        updateDurableMediaAuthorization(transition.pending)
        when (val action = transition.action) {
            is MediaAuthorizationAction.ResumeContinuation -> {
                if (continuationPresent) {
                    continuation?.resume(action.granted)
                }
            }
            is MediaAuthorizationAction.ReconcileTrash,
            is MediaAuthorizationAction.RetryRecoverableDelete,
            is MediaAuthorizationAction.ReconcileExpiredTrash,
            is MediaAuthorizationAction.ReconcileExpiredRecoverableDelete,
            -> reconcileMediaAuthorization(action, transition.pending)
            MediaAuthorizationAction.None,
            MediaAuthorizationAction.RejectNewRequest,
            -> Unit
        }
    }

    private fun restorePendingMediaAuthorization(savedInstanceState: Bundle?) {
        val stored = mediaAuthorizationStore.read()
        if (!stored.initialized) {
            mediaAuthorizationStore.write(null)
        }
        durablePendingMediaAuthorization = stored.pending
        val restoredRequestId = AndroidPendingMediaAuthorizationStore.requestIdFromBundle(
            savedInstanceState?.getBundle(STATE_PENDING_MEDIA_AUTHORIZATION),
        )
        mediaAuthorizationCallbackRequestId = restoredRequestId?.takeIf { requestId ->
            durablePendingMediaAuthorization?.requestId == requestId
        }
    }

    private fun recoverPendingMediaAuthorization() {
        val pending = durablePendingMediaAuthorization ?: return
        reconcileMediaAuthorization(mediaAuthorizationReducer.recover(pending), pending)
    }

    private fun reconcileMediaAuthorization(
        action: MediaAuthorizationAction,
        expected: PendingMediaAuthorization?,
    ) {
        if (expected == null || action == MediaAuthorizationAction.None) return
        lifecycleScope.launch {
            mediaAuthorizationGate.run {
                reconcileMediaAuthorizationNow(action, expected)
            }
        }
    }

    private suspend fun reconcilePendingBeforeNewRequest() {
        val expected = durablePendingMediaAuthorization ?: return
        reconcileMediaAuthorizationNow(mediaAuthorizationReducer.recover(expected), expected)
    }

    private suspend fun reconcileMediaAuthorizationNow(
        action: MediaAuthorizationAction,
        expected: PendingMediaAuthorization,
    ) {
        when (action) {
            is MediaAuthorizationAction.ReconcileTrash ->
                reconcileTrashAuthorization(action.uris, expected, expired = false)
            is MediaAuthorizationAction.ReconcileExpiredTrash ->
                reconcileTrashAuthorization(action.uris, expected, expired = true)
            is MediaAuthorizationAction.RetryRecoverableDelete ->
                reconcileRecoverableDelete(action.uri, expected, expired = false)
            is MediaAuthorizationAction.ReconcileExpiredRecoverableDelete ->
                reconcileRecoverableDelete(action.uri, expected, expired = true)
            MediaAuthorizationAction.None,
            MediaAuthorizationAction.RejectNewRequest,
            is MediaAuthorizationAction.ResumeContinuation,
            -> Unit
        }
    }

    private suspend fun reconcileTrashAuthorization(
        uriStrings: List<String>,
        expected: PendingMediaAuthorization,
        expired: Boolean,
    ) {
        var allStatesKnown = true
        try {
            val trashedUris = withContext(Dispatchers.IO) {
                uriStrings.mapNotNull { uriString ->
                    currentCoroutineContext().ensureActive()
                    val state = readTrashState(uriString)
                    currentCoroutineContext().ensureActive()
                    when (state) {
                        MediaTrashState.TRASHED -> uriString
                        MediaTrashState.PRESENT,
                        MediaTrashState.MISSING,
                        -> null
                        MediaTrashState.UNKNOWN -> {
                            allStatesKnown = false
                            null
                        }
                    }
                }
            }
            if (trashedUris.isNotEmpty()) {
                withContext(NonCancellable) {
                    removeMediaFromIndex(trashedUris)
                }
            }
            if (!expired && allStatesKnown) {
                completeMatchingMediaAuthorization(expected)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 已授权记录保留到下次恢复；过期记录在 finally 中释放，且不会误删 Room。
        } finally {
            if (expired) clearExpiredMediaAuthorization(expected)
        }
    }

    private suspend fun reconcileRecoverableDelete(
        uriString: String,
        expected: PendingMediaAuthorization,
        expired: Boolean,
    ) {
        var roomSyncCompleted = true
        try {
            val deleted = try {
                withContext(Dispatchers.IO) {
                    contentResolver.delete(Uri.parse(uriString), null, null) > 0
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            val shouldRemoveFromIndex = deleted || mediaUriPresence(uriString) == MediaUriPresence.MISSING
            if (shouldRemoveFromIndex) {
                try {
                    withContext(NonCancellable) {
                        removeMediaFromIndex(listOf(uriString))
                    }
                } catch (_: Exception) {
                    roomSyncCompleted = false
                }
            }
            if (!expired && roomSyncCompleted) {
                completeMatchingMediaAuthorization(expected)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (expired) clearExpiredMediaAuthorization(expected)
        }
    }

    private suspend fun mediaUriPresence(uriString: String): MediaUriPresence = withContext(Dispatchers.IO) {
        try {
            val cursor = contentResolver.query(
                Uri.parse(uriString),
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            ) ?: return@withContext MediaUriPresence.UNKNOWN
            cursor.use {
                if (it.moveToFirst()) MediaUriPresence.PRESENT else MediaUriPresence.MISSING
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MediaUriPresence.UNKNOWN
        }
    }

    private fun readTrashState(uriString: String): MediaTrashState = try {
        val cursor = contentResolver.query(
            Uri.parse(uriString),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED),
            null,
            null,
            null,
        ) ?: return MediaTrashState.UNKNOWN
        cursor.use {
            if (!it.moveToFirst()) return MediaTrashState.MISSING
            val trashedColumn = it.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            if (trashedColumn < 0) {
                MediaTrashState.UNKNOWN
            } else if (it.getInt(trashedColumn) == 1) {
                MediaTrashState.TRASHED
            } else {
                MediaTrashState.PRESENT
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MediaTrashState.UNKNOWN
    }

    private fun completeRecoverableDeleteAuthorization(uriString: String) {
        val current = durablePendingMediaAuthorization ?: return
        if (current.requestType == PendingMediaAuthorizationType.RECOVERABLE_DELETE &&
            current.currentUri == uriString
        ) {
            completeMatchingMediaAuthorization(current)
        }
    }

    private fun completeMatchingMediaAuthorization(request: PendingMediaAuthorization) {
        val current = durablePendingMediaAuthorization ?: return
        if (current.requestId != request.requestId ||
            current.requestType != request.requestType ||
            current.uris != request.uris ||
            current.currentUri != request.currentUri ||
            current.phase != PendingMediaAuthorizationPhase.GRANTED_PENDING_RECONCILIATION
        ) {
            return
        }
        updateDurableMediaAuthorization(mediaAuthorizationReducer.complete(current, current))
    }

    private fun clearExpiredMediaAuthorization(request: PendingMediaAuthorization) {
        val current = durablePendingMediaAuthorization ?: return
        if (mediaAuthorizationCallbackRequestId == request.requestId) {
            mediaAuthorizationCallbackRequestId = null
        }
        updateDurableMediaAuthorization(mediaAuthorizationReducer.clearExpired(current, request))
    }

    private fun updateDurableMediaAuthorization(pending: PendingMediaAuthorization?) {
        if (pending == durablePendingMediaAuthorization) return
        mediaAuthorizationStore.write(pending)
        durablePendingMediaAuthorization = pending
    }

    private suspend fun removeMediaFromIndex(succeededUris: List<String>) {
        (application as FrameYuApplication).database.mediaAssetDao()
            .deleteByUris(succeededUris)
    }

    private fun newMediaAuthorizationRequestId(): String =
        UUID.randomUUID().toString().replace("-", "")

    /** 在 Reader 调用内完成 1MB 分块摘要和关闭，不把 lazy 流泄露到检测器。 */
    private fun contentHashReader(): ContentHashReader = ContentHashReader { uri ->
        withContext(Dispatchers.IO) {
            val input = contentResolver.openInputStream(Uri.parse(uri)) ?: error("无法打开媒体：$uri")
            input.use { stream ->
                sha256HexCancellable(stream, HASH_BUFFER_SIZE)
            }
        }
    }

    private companion object {
        const val HASH_BUFFER_SIZE = 1024 * 1024
        const val STATE_PENDING_MEDIA_AUTHORIZATION = "pending_media_authorization"
    }

    private enum class MediaTrashState {
        TRASHED,
        PRESENT,
        MISSING,
        UNKNOWN,
    }

    private enum class MediaUriPresence {
        PRESENT,
        MISSING,
        UNKNOWN,
    }
}
