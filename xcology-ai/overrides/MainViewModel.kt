package ai.decart.example

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.decart.sdk.*
import ai.decart.sdk.realtime.*
import ai.decart.example.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val BASE_URL = "wss://api.decart.ai"
        private const val PREFS_NAME = "decart_prefs"
        private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
    }

    private val context: Context get() = getApplication()

    // SDK client - created once, reused across connect/disconnect cycles
    private var client: RealTimeClient? = null
    private var stateCollectorJob: Job? = null
    private var localStreamCollectorJob: Job? = null
    private var remoteStreamCollectorJob: Job? = null
    // Credentials are kept in memory and never built into the APK.
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private val _customPrompt = MutableStateFlow("")
    val customPrompt: StateFlow<String> = _customPrompt.asStateFlow()
    private var isFrontFacingCamera = true

    private var hasEverConnected = false

    // State
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentModel = MutableStateFlow(AppModel.RESTYLE)
    val currentModel: StateFlow<AppModel> = _currentModel.asStateFlow()

    private val _currentSkinIndex = MutableStateFlow(0)
    val currentSkinIndex: StateFlow<Int> = _currentSkinIndex.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.TRANSFORMED)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    private val _showOnboarding = MutableStateFlow(false)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    private val _localStream = MutableStateFlow<RealtimeMediaStream?>(null)
    val localStream: StateFlow<RealtimeMediaStream?> = _localStream.asStateFlow()

    private val _remoteStream = MutableStateFlow<RealtimeMediaStream?>(null)
    val remoteStream: StateFlow<RealtimeMediaStream?> = _remoteStream.asStateFlow()

    val currentSkins: List<Skin>
        get() = when (_currentModel.value) {
            AppModel.RESTYLE -> SkinLists.lucyRestyleSkins
            AppModel.EDIT -> SkinLists.lucySkins
        }

    val currentSkin: Skin
        get() = currentSkins.getOrElse(_currentSkinIndex.value) { currentSkins.first() }

    val isConnected: Boolean
        get() {
            val state = _connectionState.value
            return state == ConnectionState.CONNECTED || state == ConnectionState.GENERATING
        }

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ONBOARDING_SHOWN, false)) {
            _showOnboarding.value = true
        }
    }

    fun setApiKey(value: String) {
        disconnect()
        client?.release()
        client = null
        stateCollectorJob?.cancel()
        localStreamCollectorJob?.cancel()
        remoteStreamCollectorJob?.cancel()
        _apiKey.value = value.trim()
        _errorMessage.value = null
    }

    fun setCustomPrompt(value: String) {
        _customPrompt.value = value
        if (isConnected && value.isNotBlank()) {
            viewModelScope.launch {
                try {
                    client?.setPrompt(value, false)
                    _errorMessage.value = null
                } catch (e: Exception) {
                    _errorMessage.value = e.message ?: "Prompt update failed."
                }
            }
        }
    }

    fun streamShareToken(): String? =
        if (isConnected) client?.subscribeToken else null

    fun setReferenceImage(uri: android.net.Uri) {
        if (!isConnected) {
            _errorMessage.value = "Start the AI camera before selecting a reference."
            return
        }
        viewModelScope.launch {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Cannot read image.")
                require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) {
                    "Reference image must be under 8 MB."
                }
                val imageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                client?.setImage(
                    imageBase64,
                    _customPrompt.value.ifBlank { "Transform the scene using this visual reference." },
                    false
                )
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Reference image update failed."
            }
        }
    }

    private fun ensureClient() {
        require(_apiKey.value.isNotBlank()) { "Enter a Decart API key to connect." }
        if (client == null) {
            val rtClient = RealTimeClient(
                context = context,
                config = RealTimeClientConfig(
                    apiKey = _apiKey.value,
                    baseUrl = BASE_URL,
                    logger = AndroidLogger(LogLevel.WARN)
                )
            )
            client = rtClient

            stateCollectorJob = viewModelScope.launch {
                rtClient.connectionState.collect { state ->
                    _connectionState.value = state
                }
            }
            localStreamCollectorJob = viewModelScope.launch {
                rtClient.localStreamUpdates.collect { stream ->
                    _localStream.value = stream
                }
            }
            remoteStreamCollectorJob = viewModelScope.launch {
                rtClient.remoteStreamUpdates.collect { stream ->
                    _remoteStream.value = stream
                }
            }
        }
    }

    fun dismissOnboarding() {
        _showOnboarding.value = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
    }

    fun connect() {
        if (_apiKey.value.isBlank()) {
            _errorMessage.value = "Enter your Decart API key first."
            return
        }
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                _connectionState.value = ConnectionState.CONNECTING
                hasEverConnected = true

                ensureClient()

                val skin = currentSkin

                client!!.connect(
                    options = ConnectOptions(
                        model = _currentModel.value.realtimeModel,
                        facing = currentFacingMode(),
                        publishCamera = true,
                        onRemoteStream = { stream -> _remoteStream.value = stream },
                        initialPrompt = InitialPrompt(text = _customPrompt.value.ifBlank { skin.prompt }, enhance = false)
                    )
                )
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Could not connect to the AI server."
                _connectionState.value = ConnectionState.DISCONNECTED
                try { client?.disconnect() } catch (_: Exception) {}
                _localStream.value = null
                _remoteStream.value = null
            }
        }
    }

    fun disconnect() {
        client?.disconnect()
        _localStream.value = null
        _remoteStream.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun switchModel(model: AppModel) {
        if (model == _currentModel.value) return
        _currentModel.value = model
        _currentSkinIndex.value = 0
        if (isConnected || _connectionState.value == ConnectionState.CONNECTING) {
            disconnect()
            connect()
        }
    }

    fun switchSkin(index: Int) {
        val skins = currentSkins
        if (index < 0 || index >= skins.size) return
        _currentSkinIndex.value = index
        if (isConnected && _customPrompt.value.isBlank()) {
            viewModelScope.launch {
                client?.setPrompt(skins[index].prompt, false)
            }
        }
    }

    fun nextSkin() {
        val next = (_currentSkinIndex.value + 1) % currentSkins.size
        switchSkin(next)
    }

    fun prevSkin() {
        val prev = (_currentSkinIndex.value - 1 + currentSkins.size) % currentSkins.size
        switchSkin(prev)
    }

    fun switchCamera() {
        isFrontFacingCamera = !isFrontFacingCamera
        if (isConnected || _connectionState.value == ConnectionState.CONNECTING) {
            disconnect()
            connect()
        }
    }

    fun cycleViewMode() {
        _viewMode.value = when (_viewMode.value) {
            ViewMode.TRANSFORMED -> ViewMode.PIP
            ViewMode.PIP -> ViewMode.VERTICAL_SPLIT
            ViewMode.VERTICAL_SPLIT -> ViewMode.TRANSFORMED
        }
    }

    fun onForeground() {
        if (hasEverConnected && _connectionState.value == ConnectionState.DISCONNECTED) {
            connect()
        }
    }

    fun onBackground() {
        if (isConnected) {
            disconnect()
        }
    }

    private fun currentFacingMode(): FacingMode {
        return if (isFrontFacingCamera) FacingMode.FRONT else FacingMode.BACK
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        stateCollectorJob?.cancel()
        localStreamCollectorJob?.cancel()
        remoteStreamCollectorJob?.cancel()
        client?.release()
        client = null
    }
}
