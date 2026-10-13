package ai.decart.example.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.decart.example.MainViewModel
import ai.decart.sdk.ConnectionState

@Composable
fun CameraScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val customPrompt by viewModel.customPrompt.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var showKeyDialog by remember { mutableStateOf(true) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var keyDraft by remember { mutableStateOf("") }
    var promptDraft by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.setReferenceImage(uri)
    }
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val currentModel by viewModel.currentModel.collectAsStateWithLifecycle()
    val currentSkinIndex by viewModel.currentSkinIndex.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val showOnboarding by viewModel.showOnboarding.collectAsStateWithLifecycle()
    val localStream by viewModel.localStream.collectAsStateWithLifecycle()
    val remoteStream by viewModel.remoteStream.collectAsStateWithLifecycle()

    val isConnected = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.GENERATING
    val isConnecting = connectionState == ConnectionState.CONNECTING ||
            connectionState == ConnectionState.RECONNECTING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        if (totalDrag < -100f) viewModel.nextSkin()
                        else if (totalDrag > 100f) viewModel.prevSkin()
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    }
                )
            }
    ) {
        // Video layer
        VideoRenderer(
            localStream = localStream,
            remoteStream = remoteStream,
            viewMode = viewMode,
            modifier = Modifier.fillMaxSize()
        )

        // Top controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(80.dp))

            ModelSelector(
                currentModel = currentModel,
                onModelSelected = { viewModel.switchModel(it) }
            )

            ActionButtons(
                onFlipCamera = { viewModel.switchCamera() },
                onCycleViewMode = { viewModel.cycleViewMode() }
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .statusBarsPadding().padding(top = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xCC000000)),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { keyDraft = apiKey; showKeyDialog = true }) {
                    Text("API key")
                }
                TextButton(onClick = {
                    promptDraft = customPrompt; showPromptDialog = true
                }) {
                    Text("AI prompt")
                }
                TextButton(onClick = { imagePicker.launch("image/*") }) {
                    Text("Reference")
                }
                TextButton(onClick = {
                    val token = viewModel.streamShareToken()
                    if (token.isNullOrBlank()) {
                        Toast.makeText(context, "Connect the AI camera before sharing.", Toast.LENGTH_LONG).show()
                    } else {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, token)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share private Xcology stream token"))
                    }
                }) {
                    Text("Share feed")
                }
            }
            errorMessage?.let { message ->
                Text(message, color = Color(0xFFFFB4AB),
                    modifier = Modifier.background(Color.Black).padding(8.dp))
            }
        }

        // Bottom: style carousel
        if (isConnected) {
            StyleCarousel(
                skins = viewModel.currentSkins,
                selectedIndex = currentSkinIndex,
                onSkinSelected = { viewModel.switchSkin(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            )
        }

        // Connect button when disconnected
        if (connectionState == ConnectionState.DISCONNECTED) {
            ConnectButton(
                onClick = { viewModel.connect() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 48.dp)
            )
        }

        // Overlays
        if (isConnecting) {
            WarmingUpOverlay()
        }

        if (showOnboarding && isConnected) {
            OnboardingOverlay(onDismiss = { viewModel.dismissOnboarding() })
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Xcology AI — Decart access") },
            text = {
                Column {
                    Text("Your camera is processed by Decart online. Enter your Decart API key; it stays in app memory, not in the APK. Usage may incur charges.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("Decart API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setApiKey(keyDraft)
                    showKeyDialog = false
                }, enabled = keyDraft.isNotBlank()) { Text("Use key") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPromptDialog) {
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text("Live AI transformation") },
            text = {
                Column {
                    Text("Describe how to transform the live camera output.")
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        label = { Text("Prompt") },
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCustomPrompt(promptDraft)
                    showPromptDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) { Text("Cancel") }
            }
        )
    }
}
