package com.example.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.ai.GeminiAiService
import com.example.data.repository.ChatRepository
import com.example.domain.model.Conversation
import com.example.domain.model.GeminiModel
import com.example.domain.model.GeminiPersona
import com.example.ui.auth.signOutFromGoogle
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun GeminiChatRoute(
    currentUserId: String,
    onSignedOut: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                ChatViewModel(
                    repository = ChatRepository(db),
                    aiService = GeminiAiService(),
                    currentUserId = currentUserId,
                )
            }
        },
    )

    GeminiChatScreen(
        viewModel = viewModel,
        onSignedOut = onSignedOut,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiChatScreen(
    viewModel: ChatViewModel,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val conversationsState by viewModel.conversationsState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()

    var inputText by rememberSaveable { mutableStateOf("") }
    var showPersonaSheet by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.onPhotoPicked(context, uri)
            }
        },
    )

    val launchPhotoPicker = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    LaunchedEffect(uiState.transientNotice) {
        val notice = uiState.transientNotice
        if (!notice.isNullOrBlank()) {
            snackbarHostState.showSnackbar(notice)
            viewModel.dismissNotice()
        }
    }

    LaunchedEffect(messages.size, uiState.streamingReply?.length) {
        val totalCount = messages.size + if (uiState.streamingReply != null) 1 else 0
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = uiState.activeConversationId != null && !drawerState.isOpen) {
        viewModel.startNewChat()
    }

    val drawerBody: @Composable (Boolean) -> Unit = { isPermanent ->
        ChatDrawerContent(
            conversationsState = conversationsState,
            activeConversationId = uiState.activeConversationId,
            onNewChat = {
                viewModel.startNewChat()
                if (!isPermanent) scope.launch { drawerState.close() }
            },
            onSelectConversation = { conv ->
                viewModel.selectConversation(conv)
                if (!isPermanent) scope.launch { drawerState.close() }
            },
            onDeleteConversation = { convId ->
                viewModel.deleteConversation(convId)
            },
            onSelectPersonaQuickStart = { persona ->
                viewModel.startNewChat(persona)
                if (!isPermanent) scope.launch { drawerState.close() }
            },
            onSignOut = {
                signOutFromGoogle(
                    credentialManager = credentialManager,
                    scope = scope,
                    onSignOutComplete = onSignedOut,
                )
            },
        )
    }

    val mainScaffold: @Composable (Boolean) -> Unit = { showMenuButton ->
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    navigationIcon = {
                        if (showMenuButton) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("open_drawer_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Menu,
                                    contentDescription = stringResource(R.string.cd_open_menu),
                                )
                            }
                        }
                    },
                    title = {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showPersonaSheet = true }
                                .testTag("persona_selector_button"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GeminiSparkMark(size = 18.dp)
                                Column {
                                    Text(
                                        text = uiState.selectedPersona.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = uiState.selectedModel.badgeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.ExpandMore,
                                    contentDescription = stringResource(R.string.cd_switch_role),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.startNewChat() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("top_new_chat_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.cd_new_chat),
                            )
                        }
                    },
                )
            },
            bottomBar = {
                ComposerBar(
                    inputText = inputText,
                    onInputChanged = { inputText = it },
                    pendingAttachment = uiState.pendingAttachment,
                    isProcessingImage = uiState.isProcessingImage,
                    isGenerating = uiState.isGenerating,
                    onPickPhoto = launchPhotoPicker,
                    onClearAttachment = { viewModel.clearPendingAttachment() },
                    onQuickVisionPrompt = { quickPrompt ->
                        viewModel.sendMessage(quickPrompt)
                    },
                    onSend = {
                        val toSend = inputText
                        inputText = ""
                        viewModel.sendMessage(toSend)
                    },
                    onStop = { viewModel.stopGeneration() },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                val showWelcome = messages.isEmpty() && uiState.streamingReply == null

                if (showWelcome) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        item {
                            WelcomeHeroView(
                                selectedPersona = uiState.selectedPersona,
                                selectedModel = uiState.selectedModel,
                                onPickPhoto = launchPhotoPicker,
                                onPromptSelected = { starter ->
                                    viewModel.sendMessage(starter)
                                },
                                onOpenPersonaSheet = { showPersonaSheet = true },
                            )
                        }
                        val err = uiState.aiError
                        if (err != null) {
                            item {
                                AiErrorCard(
                                    errorBanner = err,
                                    onRetrySameModel = {
                                        viewModel.sendMessage(
                                            rawPrompt = err.failedPrompt,
                                            overrideModel = err.failedModel,
                                            retryAttachment = err.failedAttachment,
                                            skipSavingUserMessage = true,
                                        )
                                    },
                                    onRetryWithFlash = {
                                        viewModel.sendMessage(
                                            rawPrompt = err.failedPrompt,
                                            overrideModel = GeminiModel.FLASH,
                                            retryAttachment = err.failedAttachment,
                                            skipSavingUserMessage = true,
                                        )
                                    },
                                    onDismiss = { viewModel.dismissError() },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .testTag("chat_thread_list"),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp),
                    ) {
                        items(
                            items = messages,
                            key = { it.id.ifBlank { "${it.role}_${it.hashCode()}" } },
                        ) { msg ->
                            ChatMessageItem(
                                message = msg,
                                onCopyText = { copied ->
                                    clipboard.setText(AnnotatedString(copied))
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Copied response to clipboard")
                                    }
                                },
                            )
                        }

                        val streaming = uiState.streamingReply
                        if (streaming != null) {
                            item(key = "streaming_turn") {
                                ModelMessageCard(
                                    text = streaming,
                                    modelId = uiState.streamingModel?.modelId,
                                    isStreaming = true,
                                    onCopyText = {},
                                )
                            }
                        }

                        val err = uiState.aiError
                        if (err != null) {
                            item(key = "error_banner") {
                                AiErrorCard(
                                    errorBanner = err,
                                    onRetrySameModel = {
                                        viewModel.sendMessage(
                                            rawPrompt = err.failedPrompt,
                                            overrideModel = err.failedModel,
                                            retryAttachment = err.failedAttachment,
                                            skipSavingUserMessage = true,
                                        )
                                    },
                                    onRetryWithFlash = {
                                        viewModel.sendMessage(
                                            rawPrompt = err.failedPrompt,
                                            overrideModel = GeminiModel.FLASH,
                                            retryAttachment = err.failedAttachment,
                                            skipSavingUserMessage = true,
                                        )
                                    },
                                    onDismiss = { viewModel.dismissError() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp
        if (isExpanded) {
            PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.width(300.dp),
                    ) {
                        drawerBody(true)
                    }
                },
            ) {
                mainScaffold(false)
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.width(300.dp),
                    ) {
                        drawerBody(false)
                    }
                },
            ) {
                mainScaffold(true)
            }
        }
    }

    if (showPersonaSheet) {
        PersonaAndModelBottomSheet(
            selectedPersona = uiState.selectedPersona,
            selectedModel = uiState.selectedModel,
            onSelectPersona = { persona ->
                viewModel.selectPersona(persona)
            },
            onSelectModel = { model ->
                viewModel.selectModel(model)
            },
            onDismiss = { showPersonaSheet = false },
        )
    }
}

@Composable
private fun ChatDrawerContent(
    conversationsState: ConversationsUiState,
    activeConversationId: String?,
    onNewChat: () -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onSelectPersonaQuickStart: (GeminiPersona) -> Unit,
    onSignOut: () -> Unit,
) {
    val currentUser = Firebase.auth.currentUser

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GeminiSparkMark(size = 24.dp)
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Button(
            onClick = onNewChat,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("new_chat_button"),
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.new_chat),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Text(
            text = "ROLES",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GeminiPersona.entries.forEach { persona ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectPersonaQuickStart(persona) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = personaIcon(persona),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = persona.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        Text(
            text = "RECENT CHATS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(modifier = Modifier.weight(1f)) {
            when (conversationsState) {
                is ConversationsUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                is ConversationsUiState.Error -> {
                    Text(
                        text = conversationsState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is ConversationsUiState.Success -> {
                    if (conversationsState.items.isEmpty()) {
                        Text(
                            text = stringResource(R.string.empty_history_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(
                                items = conversationsState.items,
                                key = { it.id },
                            ) { conv ->
                                val isActive = conv.id == activeConversationId
                                Surface(
                                    color = if (isActive) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectConversation(conv) },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = conv.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${GeminiPersona.fromId(conv.personaId).title} • ${conv.messageCount} msgs",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteConversation(conv.id) },
                                            modifier = Modifier.minimumInteractiveComponentSize(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = stringResource(R.string.cd_delete_chat),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (currentUser?.displayName?.firstOrNull()
                            ?: currentUser?.email?.firstOrNull()
                            ?: 'G').uppercaseChar().toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column {
                    Text(
                        text = currentUser?.displayName ?: "Signed In",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = currentUser?.email.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onSignOut,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("sign_out_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = stringResource(R.string.cd_sign_out),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
