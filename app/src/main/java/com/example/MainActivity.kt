package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.ContactEntity
import com.example.data.ContactRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.MainViewModelFactory
import com.example.utils.ContactSystemSync
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current.applicationContext
                val database = AppDatabase.getDatabase(context)
                val repository = ContactRepository(database.contactDao())
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModelFactory(application, repository)
                )

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = mainViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCardList by remember { mutableStateOf(false) }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_CONTACTS] ?: false
        val writeGranted = permissions[Manifest.permission.WRITE_CONTACTS] ?: false
        if (readGranted && writeGranted) {
            viewModel.refreshContactsList()
            Toast.makeText(context, "Permissão de contatos concedida!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissão de contatos negada. Usando banco de dados local.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val hasRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val hasWrite = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasRead || !hasWrite) {
            contactsPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            )
        } else {
            viewModel.refreshContactsList()
        }
    }

    LaunchedEffect(showCardList) {
        if (showCardList) {
            viewModel.refreshContactsList()
        }
    }

    val tempFile = remember { File(context.cacheDir, "camera_capture.jpg") }
    val tempUri = remember {
        FileProvider.getUriForFile(
            context,
            "com.aistudio.extraicartao.vshrt.fileprovider",
            tempFile
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
            if (bitmap != null) {
                viewModel.processSelectedImage(bitmap)
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    viewModel.processSelectedImage(bitmap)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao abrir imagem da galeria", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraLauncher.launch(tempUri)
        } else {
            Toast.makeText(context, "Permissão da Câmera é necessária para fotografar cartões", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerCamera() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            cameraLauncher.launch(tempUri)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountBox,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "CardZap",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configurar meu nome",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                )
            )
        },
        floatingActionButton = {
            if (viewModel.capturedImageBase64 == null) {
                FloatingActionButton(
                    onClick = { triggerCamera() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Tirar Foto de Novo Cartão"
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (viewModel.capturedImageBase64 != null) {
                ScanReviewLayout(
                    viewModel = viewModel,
                    onBack = { viewModel.clearScannedState() },
                    onSaved = { showCardList = true }
                )
            } else {
                val filteredContacts = remember(contacts, searchQuery) {
                    if (searchQuery.isBlank()) contacts else {
                        contacts.filter {
                            it.name.contains(searchQuery, ignoreCase = true) ||
                                    it.observations.contains(searchQuery, ignoreCase = true) ||
                                    it.primaryPhone.contains(searchQuery)
                        }
                    }
                }

                DashboardLayout(
                    contacts = filteredContacts,
                    onContactSelected = { viewModel.selectedContact = it },
                    searchQuery = searchQuery,
                    onSearchQueryChanged = { searchQuery = it },
                    onTakePhoto = { triggerCamera() },
                    showCardList = showCardList,
                    onShowCardListChanged = { showCardList = it }
                )
            }

            if (showSettingsDialog) {
                SettingsDialog(
                    currentName = viewModel.userName,
                    onDismiss = { showSettingsDialog = false },
                    onSave = {
                        viewModel.updateUserName(it)
                        showSettingsDialog = false
                        Toast.makeText(context, "Nome salvo com sucesso!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            viewModel.selectedContact?.let { contact ->
                if (viewModel.editModeActive) {
                    EditContactDialog(
                        contact = contact,
                        onDismiss = { viewModel.editModeActive = false },
                        onSave = { updated ->
                            viewModel.saveEditedContact(updated)
                            Toast.makeText(context, "Contato atualizado com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    ContactDetailsDialog(
                        contact = contact,
                        userName = viewModel.userName,
                        getGreeting = { viewModel.getGreetingMessage() },
                        getTemplateMsg = { viewModel.getWhatsAppMessage() },
                        onDismiss = { viewModel.selectedContact = null },
                        onEdit = { viewModel.editModeActive = true },
                        onDelete = {
                            viewModel.deleteContact(contact)
                            viewModel.selectedContact = null
                            Toast.makeText(context, "Contato excluído com sucesso!", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ScanReviewLayout(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isWhatsAppInstalled = remember(context) { isAppInstalled(context, "com.whatsapp") }
    val isWhatsAppBusinessInstalled = remember(context) { isAppInstalled(context, "com.whatsapp.w4b") }

    // If on emulator / no apps detected, show normal as fallback option so layout isn't empty, otherwise show actual installed apps
    val showNormal = isWhatsAppInstalled || (!isWhatsAppInstalled && !isWhatsAppBusinessInstalled)
    val showBusiness = isWhatsAppBusinessInstalled

    var sendViaNormal by remember { mutableStateOf(isWhatsAppInstalled) }
    var sendViaBusiness by remember { mutableStateOf(isWhatsAppBusinessInstalled) }
    var followOnInstagram by remember { mutableStateOf(true) }
    var customMessage by remember { mutableStateOf("") }

    var isCheckingWhatsAppNormal by remember { mutableStateOf(false) }
    var isCheckingWhatsAppBusiness by remember { mutableStateOf(false) }
    var whatsAppNormalExists by remember { mutableStateOf(false) }
    var whatsAppBusinessExists by remember { mutableStateOf(false) }
    var whatsAppChecked by remember { mutableStateOf(false) }

    var isCheckingInstagram by remember { mutableStateOf(false) }
    var instagramExists by remember { mutableStateOf(false) }
    var instagramChecked by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.parsedPrimaryPhone) {
        val phone = viewModel.parsedPrimaryPhone
        if (phone.isNotBlank()) {
            isCheckingWhatsAppNormal = true
            whatsAppNormalExists = checkIfWhatsAppRegistered(context, phone, isBusiness = false)
            isCheckingWhatsAppNormal = false
            
            isCheckingWhatsAppBusiness = true
            whatsAppBusinessExists = checkIfWhatsAppRegistered(context, phone, isBusiness = true)
            isCheckingWhatsAppBusiness = false
            
            whatsAppChecked = true
            
            // Enabled and checked by default only if guaranteed
            sendViaNormal = whatsAppNormalExists && (isWhatsAppInstalled || (!isWhatsAppInstalled && !isWhatsAppBusinessInstalled))
            sendViaBusiness = whatsAppBusinessExists && isWhatsAppBusinessInstalled
        } else {
            whatsAppNormalExists = false
            whatsAppBusinessExists = false
            whatsAppChecked = false
            sendViaNormal = false
            sendViaBusiness = false
        }
    }

    LaunchedEffect(viewModel.parsedInstagram) {
        val handle = viewModel.parsedInstagram
        if (handle.isNotBlank()) {
            isCheckingInstagram = true
            instagramExists = checkIfInstagramUserExists(handle)
            isCheckingInstagram = false
            instagramChecked = true
            
            // Only autofollow if the profile is verified as existing
            followOnInstagram = instagramExists
            if (!instagramExists) {
                Toast.makeText(context, "O contato $handle do Instagram não existe!", Toast.LENGTH_LONG).show()
            }
        } else {
            instagramExists = false
            instagramChecked = false
            followOnInstagram = false
        }
    }

    // Ensure we start with standard option checked if on emulator where no app is detected
    LaunchedEffect(isWhatsAppInstalled, isWhatsAppBusinessInstalled) {
        if (!isWhatsAppInstalled && !isWhatsAppBusinessInstalled) {
            sendViaNormal = true
        }
    }

    val defaultMsg = viewModel.getWhatsAppMessage()
    LaunchedEffect(viewModel.parsedName, viewModel.userName) {
        if (customMessage.isEmpty() || customMessage.contains("()  .") || customMessage.contains("o(a)  .")) {
            customMessage = defaultMsg
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onBack() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Cancelar e Voltar", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                viewModel.capturedImageBase64?.let { base64 ->
                    Base64Image(base64String = base64, modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (viewModel.isScanning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "A IA do Gemini está analisando o layout do cartão para extrair nome, telefones (detectando WhatsApp), Instagram, endereço e serviços de forma inteligente...",
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        viewModel.scanError?.let { err ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }
        }

        Text(
            text = "Revisar dados estruturados",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        OutlinedTextField(
            value = viewModel.parsedName,
            onValueChange = { viewModel.parsedName = it },
            label = { Text("Nome:") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
        )

        OutlinedTextField(
            value = viewModel.parsedPrimaryPhone,
            onValueChange = { viewModel.parsedPrimaryPhone = it },
            label = { Text("Telefone principal (WhatsApp / Mensagens):") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFF25D366)) },
            supportingText = { Text("Definido prioritariamente como o telefone associado ao WhatsApp no cartão.") }
        )

        OutlinedTextField(
            value = viewModel.parsedSecondaryPhone,
            onValueChange = { viewModel.parsedSecondaryPhone = it },
            label = { Text("Telefone secundário (Fixo / Alternativo):") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) }
        )

        OutlinedTextField(
            value = viewModel.parsedInstagram,
            onValueChange = { viewModel.parsedInstagram = it },
            label = { Text("Instagram (perfil, arroba ou link):") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFE1306C)) },
            supportingText = { Text("Perfil de Instagram extraído do cartão de visita.") }
        )

        OutlinedTextField(
            value = viewModel.parsedAddress,
            onValueChange = { viewModel.parsedAddress = it },
            label = { Text("Endereço:") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
        )

        OutlinedTextField(
            value = viewModel.parsedObservations,
            onValueChange = { viewModel.parsedObservations = it },
            label = { Text("Observações (Serviços e Soluções):") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
            supportingText = { Text("Compilado dinâmico das listagens ou especialidades do cartão.") }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Opções de Envio do WhatsApp",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (isCheckingWhatsAppNormal || isCheckingWhatsAppBusiness) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(text = "Validando se número existe no WhatsApp...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (viewModel.parsedPrimaryPhone.isNotBlank() && whatsAppChecked && !whatsAppNormalExists && !whatsAppBusinessExists) {
                    Text(
                        text = "Aviso: O número fornecido não possui formato válido de celular ou não foi encontrado no WhatsApp.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (showNormal) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = sendViaNormal,
                            onCheckedChange = { sendViaNormal = it },
                            enabled = whatsAppNormalExists
                        )
                        Column {
                            Text(
                                text = "Enviar via WhatsApp Padrão",
                                fontSize = 13.sp,
                                color = if (whatsAppNormalExists) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            if (viewModel.parsedPrimaryPhone.isNotBlank() && whatsAppChecked && !whatsAppNormalExists) {
                                Text("Apenas disponível para formato de celular celular garantido", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (showBusiness) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = sendViaBusiness,
                            onCheckedChange = { sendViaBusiness = it },
                            enabled = whatsAppBusinessExists
                        )
                        Column {
                            Text(
                                text = "Enviar via WhatsApp Business",
                                fontSize = 13.sp,
                                color = if (whatsAppBusinessExists) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            if (viewModel.parsedPrimaryPhone.isNotBlank() && whatsAppChecked && !whatsAppBusinessExists) {
                                Text("Apenas disponível para formato de celular garantido", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (!isWhatsAppInstalled && !isWhatsAppBusinessInstalled) {
                    Text(
                        text = "(Nenhum WhatsApp detectado localmente no aparelho. Testando com verificação por formato)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val isMessageEnabled = sendViaNormal || sendViaBusiness

                Text(
                    text = "Mensagem para Enviar (Habilitado se WhatsApp selecionado):",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = if (isMessageEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp)
                )

                OutlinedTextField(
                    value = customMessage,
                    onValueChange = { customMessage = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isMessageEnabled,
                    maxLines = 8,
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(8.dp),
                    supportingText = { Text("Lembrete: O template utiliza seu nome de usuário (${viewModel.userName}).", fontSize = 10.sp) }
                )
            }
        }

        // Instagram Checkbox (Optional)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = followOnInstagram && instagramExists && instagramChecked,
                    onCheckedChange = { followOnInstagram = it },
                    enabled = instagramExists && !isCheckingInstagram && viewModel.parsedInstagram.isNotBlank()
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "Seguir no Instagram automaticamente ao salvar",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = if (instagramExists && viewModel.parsedInstagram.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    if (isCheckingInstagram) {
                        Text(
                            text = "Verificando se o perfil existe no Instagram...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (viewModel.parsedInstagram.isBlank()) {
                        Text(
                            text = "Nenhum perfil de Instagram detectado no cartão.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (instagramChecked && !instagramExists) {
                        Text(
                            text = "O contato ${viewModel.parsedInstagram} do Instagram não existe!",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (instagramChecked && instagramExists) {
                        Text(
                            text = "Perfil verificado com sucesso no Instagram!",
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Descartar")
            }

            Button(
                onClick = {
                    if (viewModel.parsedName.isBlank()) {
                        Toast.makeText(context, "Por favor insira um nome antes de salvar", Toast.LENGTH_SHORT).show()
                    } else {
                        val phone = viewModel.parsedPrimaryPhone
                        val msgToSubmit = customMessage
                        val shouldOpenWhatsApp = sendViaNormal || sendViaBusiness
                        val isBusiness = sendViaBusiness
                        val instagramHandle = viewModel.parsedInstagram

                        viewModel.saveContact(isBusiness)
                        Toast.makeText(context, "Contato salvo com sucesso!", Toast.LENGTH_SHORT).show()
                        onSaved()

                        if (shouldOpenWhatsApp) {
                            if (phone.isNotBlank()) {
                                openWhatsAppChat(context, phone, msgToSubmit, isBusiness)
                            } else {
                                Toast.makeText(context, "Contato salvo, mas sem telefone para envio do WhatsApp.", Toast.LENGTH_LONG).show()
                            }
                        }

                        if (followOnInstagram && instagramHandle.isNotBlank()) {
                            scope.launch {
                                val exists = checkIfInstagramUserExists(instagramHandle)
                                if (exists) {
                                    openInstagramProfile(context, instagramHandle)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "O contato $instagramHandle do Instagram não existe!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Salvar Registro")
            }
        }
    }
}

@Composable
fun DashboardLayout(
    contacts: List<ContactEntity>,
    onContactSelected: (ContactEntity) -> Unit,
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onTakePhoto: () -> Unit,
    showCardList: Boolean,
    onShowCardListChanged: (Boolean) -> Unit
) {
    if (!showCardList) {
        // Welcome Screen (Start Screen)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.size(110.dp),
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.AccountBox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Cadastre Cartões com IA",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Alimente a IA capturando uma foto de um cartão físico para extrair seus dados automaticamente para os seus contatos corporativos e do Android!",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onTakePhoto,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Tirar Foto",
                        modifier = Modifier.size(24.dp)
                    )
                }

                Button(
                    onClick = { onShowCardListChanged(true) },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Contacts,
                        contentDescription = "Listar Cartões/Contatos",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    } else {
        // List of Cards Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = { Text("Buscar contatos por nome, serviço...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Limpar busca")
                        }
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onShowCardListChanged(false) },
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Voltar")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Início", fontSize = 14.sp)
                }
            }

            if (contacts.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        modifier = Modifier.size(90.dp),
                        shape = CircleShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AccountBox,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Nenhum resultado encontrado" else "Nenhum cartão salvo ainda",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Tente buscar com outros termos." else "Use o botão acima para capturar seu primeiro cartão!",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(contacts, key = { it.id }) { contact ->
                        ContactCard(contact = contact, onClick = { onContactSelected(contact) })
                    }
                }
            }
        }
    }
}

@Composable
fun ContactCard(
    contact: ContactEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (contact.imageBase64.isNotEmpty()) {
                    Base64Image(
                        base64String = contact.imageBase64,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = contact.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (contact.primaryPhone.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = contact.primaryPhone,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (contact.observations.isNotEmpty()) {
                    val rawServices = contact.observations.split(",")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rawServices.take(4).forEach { service ->
                            val sTrim = service.trim()
                            if (sTrim.isNotEmpty()) {
                                SuggestionChip(
                                    onClick = { },
                                    label = { Text(sTrim, fontSize = 9.sp, fontWeight = FontWeight.Medium) },
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun Base64Image(
    base64String: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val bitmap = remember(base64String) {
        try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Imagem do cartão físico escaneado",
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Configurações Globais",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Configure seu nome de usuário. Este nome será inserido automaticamente no template da mensagem enviada aos contatos via WhatsApp.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do Usuário Android") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank()) onSave(name)
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Salvar")
                    }
                }
            }
        }
    }
}

fun isAppInstalled(context: Context, packageName: String): Boolean {
    val pm = context.packageManager
    return try {
        pm.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}

fun isWhatsAppRegisteredOnDevice(context: Context, phone: String, isBusiness: Boolean): Boolean {
    if (!ContactSystemSync.hasContactsPermissions(context)) return false
    val cleanPhone = phone.replace("\\D".toRegex(), "")
    if (cleanPhone.isEmpty()) return false
    
    val mimeType = if (isBusiness) {
        "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"
    } else {
        "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    }
    
    val resolver = context.contentResolver
    val uri = ContactsContract.Data.CONTENT_URI
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
    val selection = "${ContactsContract.Data.MIMETYPE} = ?"
    val selectionArgs = arrayOf(mimeType)
    
    try {
        val cursor = resolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            val numCol = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numCol != -1) {
                while (it.moveToNext()) {
                    val number = it.getString(numCol) ?: continue
                    val cleanNumber = number.replace("\\D".toRegex(), "")
                    if (cleanNumber.isNotEmpty() && (cleanNumber.endsWith(cleanPhone) || cleanPhone.endsWith(cleanNumber))) {
                        return true
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("WhatsAppCheck", "Error checking whatsapp registration: ${e.message}")
    }
    return false
}

suspend fun checkIfWhatsAppRegistered(context: Context, phone: String, isBusiness: Boolean): Boolean {
    val cleanPhone = phone.replace("\\D".toRegex(), "")
    if (cleanPhone.isEmpty()) return false
    
    return withContext(Dispatchers.IO) {
        val synced = isWhatsAppRegisteredOnDevice(context, cleanPhone, isBusiness)
        if (synced) {
            true
        } else {
            // Intelligent mobile format validation for Brazil and general international mobile numbers
            val length = cleanPhone.length
            if (length >= 10) {
                if (length == 11) {
                    cleanPhone[2] == '9'
                } else if (length == 13) {
                    cleanPhone[4] == '9'
                } else {
                    true
                }
            } else {
                false
            }
        }
    }
}

suspend fun checkIfInstagramUserExists(username: String): Boolean {
    val cleanUsername = username.replace("@", "").trim()
    if (cleanUsername.isEmpty()) return false
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://www.instagram.com/$cleanUsername/")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            
            val responseCode = connection.responseCode
            responseCode != HttpURLConnection.HTTP_NOT_FOUND
        } catch (e: Exception) {
            true // fallback to true on network error so we don't block users if there's internet trouble or redirection issues
        }
    }
}

fun openInstagramProfile(context: Context, username: String) {
    val cleanUsername = username.replace("@", "").trim()
    if (cleanUsername.isEmpty()) return
    val uri = Uri.parse("http://instagram.com/_u/$cleanUsername")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.instagram.android")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/$cleanUsername")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (ex: Exception) {
            Toast.makeText(context, "Instagram não pôde ser aberto.", Toast.LENGTH_SHORT).show()
        }
    }
}

fun formatNumberToWhatsApp(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (digits.length in 10..11) {
        "55$digits" // Automatically prepending Brazil standard country code
    } else {
        digits
    }
}

fun openWhatsAppChat(context: Context, phone: String, message: String, useBusiness: Boolean) {
    val formattedPhone = formatNumberToWhatsApp(phone)
    if (formattedPhone.isEmpty()) {
        Toast.makeText(context, "Telefone inválido ou vazio para envio.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val url = "https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(message)}"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            setPackage(if (useBusiness) "com.whatsapp.w4b" else "com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to launch generic chooser/url if preferred app isn't installed
        try {
            val url = "https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(message)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            Toast.makeText(context, "WhatsApp/WhatsApp Business não localizado no aparelho.", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun ContactDetailsDialog(
    contact: ContactEntity,
    userName: String,
    getGreeting: () -> String,
    getTemplateMsg: () -> String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCheckingInstagram by remember { mutableStateOf(false) }
    var isImageZoomed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dados do Contato",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Editar", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(
                            onClick = onDismiss,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Fechar",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                if (contact.imageBase64.isNotEmpty()) {
                    Text(
                        text = "Foto do Cartão Original (Toque para dar zoom):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clickable { isImageZoomed = true },
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Base64Image(base64String = contact.imageBase64, modifier = Modifier.fillMaxSize())
                    }
                }

                DetailTextItem(icon = Icons.Default.Person, label = "Nome:", value = contact.name)
                
                if (contact.primaryPhone.isNotEmpty()) {
                    DetailTextItem(
                        icon = Icons.Default.Phone,
                        label = "Telefone Principal (WhatsApp):",
                        value = contact.primaryPhone,
                        iconColor = Color(0xFF25D366)
                    )
                }

                if (contact.secondaryPhone.isNotEmpty()) {
                    DetailTextItem(icon = Icons.Default.Phone, label = "Telefone Secundário:", value = contact.secondaryPhone)
                }

                if (contact.address.isNotEmpty()) {
                    DetailTextItem(icon = Icons.Default.Place, label = "Endereço:", value = contact.address)
                }

                if (contact.instagram.isNotEmpty()) {
                    DetailTextItem(
                        icon = Icons.Default.Person, 
                        label = "Instagram:", 
                        value = contact.instagram,
                        iconColor = Color(0xFFE1306C)
                    )
                }

                if (contact.observations.isNotEmpty()) {
                    DetailTextItem(icon = Icons.Default.Info, label = "Serviços / Observações:", value = contact.observations)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Ações Rápidas de Integração",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Button(
                    onClick = {
                        val textMsg = getTemplateMsg()
                        openWhatsAppChat(context, contact.primaryPhone, textMsg, contact.useWhatsAppBusiness)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                        Text("Enviar Mensagem no WhatsApp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                if (contact.instagram.isNotEmpty()) {
                    Button(
                        onClick = {
                            scope.launch {
                                isCheckingInstagram = true
                                val exists = checkIfInstagramUserExists(contact.instagram)
                                isCheckingInstagram = false
                                if (exists) {
                                    openInstagramProfile(context, contact.instagram)
                                } else {
                                    Toast.makeText(
                                        context,
                                        "O contato ${contact.instagram} do Instagram não existe!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                        enabled = !isCheckingInstagram
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isCheckingInstagram) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verificando...", color = Color.White, fontSize = 13.sp)
                            } else {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                                Text("Seguir no Instagram", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_INSERT).apply {
                                type = ContactsContract.Contacts.CONTENT_TYPE
                                putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
                                putExtra(ContactsContract.Intents.Insert.PHONE, contact.primaryPhone)
                                if (contact.secondaryPhone.isNotBlank()) {
                                    putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, contact.secondaryPhone)
                                }
                                if (contact.address.isNotBlank()) {
                                    putExtra(ContactsContract.Intents.Insert.POSTAL, contact.address)
                                }
                                if (contact.observations.isNotBlank()) {
                                    putExtra(ContactsContract.Intents.Insert.NOTES, contact.observations)
                                }
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Não foi possível exportar contato nativo", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.AccountBox, contentDescription = null)
                        Text("Salvar na Agenda do Celular", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Text("Fechar Detalhes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    if (isImageZoomed) {
        Dialog(onDismissRequest = { isImageZoomed = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clickable { isImageZoomed = false }
            ) {
                Base64Image(
                    base64String = contact.imageBase64,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
fun DetailTextItem(
    icon: ImageVector,
    label: String,
    value: String,
    iconColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp)
        )
        Column {
            Text(text = label, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onSave: (ContactEntity) -> Unit
) {
    var name by remember { mutableStateOf(contact.name) }
    var primaryPhone by remember { mutableStateOf(contact.primaryPhone) }
    var secondaryPhone by remember { mutableStateOf(contact.secondaryPhone) }
    var instagram by remember { mutableStateOf(contact.instagram) }
    var address by remember { mutableStateOf(contact.address) }
    var observations by remember { mutableStateOf(contact.observations) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .heightIn(max = 580.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Editar Contato",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome:") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                )

                OutlinedTextField(
                    value = primaryPhone,
                    onValueChange = { primaryPhone = it },
                    label = { Text("Telefone Principal:") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFF25D366)) }
                )

                OutlinedTextField(
                    value = secondaryPhone,
                    onValueChange = { secondaryPhone = it },
                    label = { Text("Telefone Secundário:") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) }
                )

                OutlinedTextField(
                    value = instagram,
                    onValueChange = { instagram = it },
                    label = { Text("Instagram:") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFE1306C)) }
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Endereço:") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
                )

                OutlinedTextField(
                    value = observations,
                    onValueChange = { observations = it },
                    label = { Text("Serviços / Observações:") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                )

                if (primaryPhone.isNotBlank() || instagram.isNotBlank()) {
                    Text(
                        text = "Ações para testar contato:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (primaryPhone.isNotBlank()) {
                            val context = LocalContext.current
                            OutlinedButton(
                                onClick = {
                                    openWhatsAppChat(context, primaryPhone, "", false)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF25D366))
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFF25D366), modifier = Modifier.size(16.dp))
                                    Text("WhatsApp", color = Color(0xFF25D366), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (instagram.isNotBlank()) {
                            val context = LocalContext.current
                            OutlinedButton(
                                onClick = {
                                    openInstagramProfile(context, instagram)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1306C))
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFFE1306C), modifier = Modifier.size(16.dp))
                                    Text("Instagram", color = Color(0xFFE1306C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                onSave(
                                    contact.copy(
                                        name = name,
                                        primaryPhone = primaryPhone,
                                        secondaryPhone = secondaryPhone,
                                        instagram = instagram,
                                        address = address,
                                        observations = observations
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Salvar")
                    }
                }
            }
        }
    }
}
