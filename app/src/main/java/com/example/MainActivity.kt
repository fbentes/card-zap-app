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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
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

    LaunchedEffect(viewModel.isScanning) {
        if (!viewModel.isScanning && viewModel.capturedImageBase64 != null && viewModel.scanError == null) {
            val name = viewModel.parsedName
            val phone = viewModel.parsedPrimaryPhone
            val cleanPhone = phone.replace("\\D".toRegex(), "")
            if (name.isNotBlank() || cleanPhone.isNotBlank()) {
                val existing = contacts.find { lc ->
                    val cleanLcPrimary = lc.primaryPhone.replace("\\D".toRegex(), "")
                    val cleanLcSecondary = lc.secondaryPhone.replace("\\D".toRegex(), "")
                    (cleanPhone.isNotEmpty() && (cleanLcPrimary == cleanPhone || cleanLcSecondary == cleanPhone || cleanLcPrimary.endsWith(cleanPhone) || cleanPhone.endsWith(cleanLcPrimary))) ||
                    (name.isNotBlank() && lc.name.trim().equals(name.trim(), ignoreCase = true))
                }
                if (existing != null) {
                    val merged = existing.copy(
                        name = if (viewModel.parsedName.isNotBlank()) viewModel.parsedName else existing.name,
                        primaryPhone = if (viewModel.parsedPrimaryPhone.isNotBlank()) viewModel.parsedPrimaryPhone else existing.primaryPhone,
                        secondaryPhone = if (viewModel.parsedSecondaryPhone.isNotBlank()) viewModel.parsedSecondaryPhone else existing.secondaryPhone,
                        address = if (viewModel.parsedAddress.isNotBlank()) viewModel.parsedAddress else existing.address,
                        observations = if (viewModel.parsedObservations.isNotBlank()) viewModel.parsedObservations else existing.observations,
                        instagram = if (viewModel.parsedInstagram.isNotBlank()) viewModel.parsedInstagram else existing.instagram,
                        imageBase64 = viewModel.capturedImageBase64 ?: existing.imageBase64
                    )
                    viewModel.selectedContact = merged
                    viewModel.editModeActive = true
                    viewModel.clearScannedState()
                    Toast.makeText(context, "Contato já existe (${existing.name}). Abrindo edição para evitar redundância.", Toast.LENGTH_LONG).show()
                }
            }
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
                navigationIcon = {
                    if (showCardList && viewModel.capturedImageBase64 == null) {
                        IconButton(onClick = { showCardList = false }) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Voltar para tela inicial"
                            )
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_icon_zc_1780517771865),
                            contentDescription = "CardZap Logo",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
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
                        onDismiss = { 
                            viewModel.editModeActive = false 
                            viewModel.selectedContact = null
                        },
                        onSave = { updated ->
                            viewModel.saveEditedContact(updated)
                            viewModel.editModeActive = false
                            viewModel.selectedContact = null
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
                        },
                        onUpdateContact = { updated ->
                            viewModel.saveEditedContact(updated)
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

    val contactsList by viewModel.contacts.collectAsStateWithLifecycle()

    val cleanParsedPhone = remember(viewModel.parsedPrimaryPhone) {
        viewModel.parsedPrimaryPhone.replace("\\D".toRegex(), "")
    }
    val existingWhatsAppContact = remember(contactsList, cleanParsedPhone) {
        if (cleanParsedPhone.isEmpty()) null else {
            contactsList.find { lc ->
                val cleanLcPrimary = lc.primaryPhone.replace("\\D".toRegex(), "")
                val cleanLcSecondary = lc.secondaryPhone.replace("\\D".toRegex(), "")
                (cleanLcPrimary.isNotEmpty() && (cleanLcPrimary == cleanParsedPhone || cleanLcPrimary.endsWith(cleanParsedPhone) || cleanParsedPhone.endsWith(cleanLcPrimary))) ||
                (cleanLcSecondary.isNotEmpty() && (cleanLcSecondary == cleanParsedPhone || cleanLcSecondary.endsWith(cleanParsedPhone) || cleanParsedPhone.endsWith(cleanLcSecondary)))
            }
        }
    }

    val cleanParsedInstagram = remember(viewModel.parsedInstagram) {
        viewModel.parsedInstagram.replace("@", "").trim().lowercase()
    }
    val existingInstagramContact = remember(contactsList, cleanParsedInstagram) {
        if (cleanParsedInstagram.isEmpty()) null else {
            contactsList.find { lc ->
                val cleanLcInstagram = lc.instagram.replace("@", "").trim().lowercase()
                cleanLcInstagram == cleanParsedInstagram
            }
        }
    }

    val isWhatsAppInstalled = remember(context) { isAppInstalled(context, "com.whatsapp") }
    val isWhatsAppBusinessInstalled = remember(context) { isAppInstalled(context, "com.whatsapp.w4b") }

    // If on emulator / no apps detected, show normal as fallback option so layout isn't empty, otherwise show actual installed apps
    val showNormal = isWhatsAppInstalled || (!isWhatsAppInstalled && !isWhatsAppBusinessInstalled)
    val showBusiness = isWhatsAppBusinessInstalled

    var sendViaNormal by remember { mutableStateOf(false) }
    var sendViaBusiness by remember { mutableStateOf(false) }
    var followOnInstagram by remember { mutableStateOf(false) }
    var customMessage by remember { mutableStateOf("") }

    LaunchedEffect(existingWhatsAppContact) {
        if (existingWhatsAppContact != null) {
            sendViaNormal = false
            sendViaBusiness = false
        }
    }

    LaunchedEffect(existingInstagramContact) {
        if (existingInstagramContact != null) {
            followOnInstagram = false
        }
    }

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
            
            // Checkboxes remain unchecked by default per user request
            sendViaNormal = false
            sendViaBusiness = false
        } else {
            whatsAppNormalExists = false
            whatsAppBusinessExists = false
            whatsAppChecked = false
            sendViaNormal = false
            sendViaBusiness = false
        }
    }

    // Force send states to false if WhatsApp connectivity does not exist
    LaunchedEffect(whatsAppNormalExists, whatsAppBusinessExists, existingWhatsAppContact) {
        if (existingWhatsAppContact != null) {
            sendViaNormal = false
            sendViaBusiness = false
        } else {
            if (!whatsAppNormalExists) {
                sendViaNormal = false
            }
            if (!whatsAppBusinessExists) {
                sendViaBusiness = false
            }
        }
    }

    LaunchedEffect(viewModel.parsedInstagram) {
        val handle = viewModel.parsedInstagram
        if (handle.isNotBlank()) {
            isCheckingInstagram = true
            instagramExists = checkIfInstagramUserExists(handle)
            isCheckingInstagram = false
            instagramChecked = true
            
            // Keep default of opening Instagram to false/unchecked to enhance save speed/usability
            followOnInstagram = false
            if (!instagramExists) {
                Toast.makeText(context, "O contato $handle do Instagram não existe!", Toast.LENGTH_LONG).show()
            }
        } else {
            instagramExists = false
            instagramChecked = false
            followOnInstagram = false
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
            leadingIcon = { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp)) },
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

                if (existingWhatsAppContact != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Aviso contato whatsapp existente",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "whatsapp: O ${viewModel.parsedPrimaryPhone} já existe como ${existingWhatsAppContact.name} !",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

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
                            enabled = whatsAppNormalExists && existingWhatsAppContact == null
                        )
                        Column {
                            Text(
                                text = "Enviar via WhatsApp Padrão",
                                fontSize = 13.sp,
                                color = if (whatsAppNormalExists && existingWhatsAppContact == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                            enabled = whatsAppBusinessExists && existingWhatsAppContact == null
                        )
                        Column {
                            Text(
                                text = "Enviar via WhatsApp Business",
                                fontSize = 13.sp,
                                color = if (whatsAppBusinessExists && existingWhatsAppContact == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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

                val isMessageEnabled = (sendViaNormal || sendViaBusiness) && existingWhatsAppContact == null

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
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (existingInstagramContact != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Aviso contato instagram existente",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "instagram: O instagram ${viewModel.parsedInstagram} já está sendo seguido !",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = followOnInstagram,
                        onCheckedChange = { followOnInstagram = it },
                        enabled = instagramExists && !isCheckingInstagram && viewModel.parsedInstagram.isNotBlank() && existingInstagramContact == null
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = "Abrir o Instagram ao salvar para seguir o contato",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (instagramExists && viewModel.parsedInstagram.isNotBlank() && existingInstagramContact == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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
                        } else if (instagramChecked && instagramExists && existingInstagramContact == null) {
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

                        viewModel.saveContact(isBusiness, followOnInstagram)
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
        Box(modifier = Modifier.fillMaxSize()) {
            // High-fidelity active dark/royal blue gradient background
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF031B33), // Deep Navy
                            Color(0xFF0D3261), // Royal Slate Blue
                            Color(0xFF16529E)  // Active Vibrant Blue
                        )
                    )
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Section
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "CardZap Digitalizador",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Escarneie e digitalize cartões físicos com IA",
                        fontSize = 13.5.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                }

                // 3D-oriented layered cascading physical business cards simulation area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Card 1: Back (Carlos Lima - Carpentry & Painting)
                    Card(
                        modifier = Modifier
                            .width(260.dp)
                            .height(135.dp)
                            .graphicsLayer(
                                rotationZ = -12f,
                                translationX = -30f,
                                translationY = -25f
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), // Deep dark slate
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("CARLOS LIMA", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF00D215))
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                            Text("Reformas Residenciais e Pinturas", fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("(11) 98765-4321", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
                                Text("São Paulo - SP", fontSize = 9.sp, color = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }

                    // Card 2: Middle (Clínica Vida - Dra. Beatriz)
                    Card(
                        modifier = Modifier
                            .width(260.dp)
                            .height(135.dp)
                            .graphicsLayer(
                                rotationZ = 8f,
                                translationX = 25f,
                                translationY = 5f
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.15f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("CLÍNICA VIDA", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = Color(0xFF0056C6))
                                Icon(Icons.Default.Place, contentDescription = null, tint = Color(0xFFE1306C), modifier = Modifier.size(16.dp))
                            }
                            Text("Dra. Beatriz Sousa | Pediatra", fontSize = 10.sp, color = Color.Gray)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("(21) 2211-1313", fontSize = 9.sp, color = Color.DarkGray)
                                Text("Rio de Janeiro - RJ", fontSize = 9.sp, color = Color.DarkGray)
                            }
                        }
                    }

                    // Card 3: Front / Sharp (Ana Silva - Design Studio)
                    val scannerColor = Color(0xFF00E5FF)
                    Card(
                        modifier = Modifier
                            .width(270.dp)
                            .height(145.dp)
                            .graphicsLayer(
                                rotationZ = -2f,
                                translationY = 32f
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        border = BorderStroke(1.5.dp, scannerColor), // Glowing teal scan line effect border
                        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier.padding(16.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("ANA SILVA", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF0F172A))
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(18.dp))
                                }
                                Text("UX Designer & Branding", fontSize = 11.sp, color = Color(0xFF64748B))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("contato@anasilva.design", fontSize = 10.sp, color = Color(0xFF334155))
                                    Text("Portfólio online", fontSize = 9.sp, color = Color(0xFF64748B))
                                }
                            }

                            // Scanner focus Corner lines to simulate scanner
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height
                                val lineSize = 16.dp.toPx()
                                val strokeW = 3.dp.toPx()
                                
                                // Top-Left
                                drawLine(scannerColor, Offset(0f, 0f), Offset(lineSize, 0f), strokeWidth = strokeW)
                                drawLine(scannerColor, Offset(0f, 0f), Offset(0f, lineSize), strokeWidth = strokeW)
                                
                                // Top-Right
                                drawLine(scannerColor, Offset(w, 0f), Offset(w - lineSize, 0f), strokeWidth = strokeW)
                                drawLine(scannerColor, Offset(w, 0f), Offset(w, lineSize), strokeWidth = strokeW)
                                
                                // Bottom-Left
                                drawLine(scannerColor, Offset(0f, h), Offset(lineSize, h), strokeWidth = strokeW)
                                drawLine(scannerColor, Offset(0f, h), Offset(0f, h - lineSize), strokeWidth = strokeW)
                                
                                // Bottom-Right
                                drawLine(scannerColor, Offset(w, h), Offset(w - lineSize, h), strokeWidth = strokeW)
                                drawLine(scannerColor, Offset(w, h), Offset(w, h - lineSize), strokeWidth = strokeW)
                            }
                        }
                    }
                }

                // Interactive Primary Call to Actions Section (Central Camera Trigger)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    // Beautiful floating central camera trigger button
                    Button(
                        onClick = { onTakePhoto() },
                        modifier = Modifier
                            .size(76.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF0D3261)
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Tirar foto",
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // List existing local contact registry cards button
                    ElevatedButton(
                        onClick = { onShowCardListChanged(true) },
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = Color.White.copy(alpha = 0.15f),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contacts,
                            contentDescription = "Listar Cartões/Contatos",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Ver Meus Contatos",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
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

            BackHandler(enabled = showCardList) {
                onShowCardListChanged(false)
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
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(14.dp)
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
    onDelete: () -> Unit,
    onUpdateContact: (ContactEntity) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isImageZoomed by remember { mutableStateOf(false) }

    var isWhatsAppRegisteredOnNormal by remember { mutableStateOf<Boolean?>(null) }
    var isWhatsAppRegisteredOnBusiness by remember { mutableStateOf<Boolean?>(null) }
    var isCheckingWhatsApp by remember { mutableStateOf(false) }

    var isCheckingInstagramOnLoad by remember { mutableStateOf(false) }
    var instagramExists by remember { mutableStateOf(true) }

    val isNormalInstalled = remember(context) { isAppInstalled(context, "com.whatsapp") }
    val isBusinessInstalled = remember(context) { isAppInstalled(context, "com.whatsapp.w4b") }

    LaunchedEffect(contact.primaryPhone) {
        if (contact.primaryPhone.isNotEmpty()) {
            isCheckingWhatsApp = true
            isWhatsAppRegisteredOnNormal = checkIfWhatsAppRegistered(context, contact.primaryPhone, isBusiness = false)
            isWhatsAppRegisteredOnBusiness = checkIfWhatsAppRegistered(context, contact.primaryPhone, isBusiness = true)
            isCheckingWhatsApp = false
        }
    }

    LaunchedEffect(contact.instagram) {
        if (contact.instagram.isNotEmpty()) {
            isCheckingInstagramOnLoad = true
            instagramExists = checkIfInstagramUserExists(contact.instagram)
            isCheckingInstagramOnLoad = false
        }
    }

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Editar", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Fechar",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom),
                        label = "Telefone Principal (WhatsApp):",
                        value = contact.primaryPhone
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

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (contact.primaryPhone.isNotEmpty()) {
                        if (isNormalInstalled) {
                            Button(
                                onClick = {
                                    val textMsg = getTemplateMsg()
                                    openWhatsAppChat(context, contact.primaryPhone, textMsg, false)
                                },
                                modifier = Modifier.size(54.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(0.dp),
                                enabled = !isCheckingWhatsApp
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom),
                                    contentDescription = "WhatsApp Padrão",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(54.dp)
                                )
                            }
                        }

                        if (isBusinessInstalled) {
                            Button(
                                onClick = {
                                    val textMsg = getTemplateMsg()
                                    openWhatsAppChat(context, contact.primaryPhone, textMsg, true)
                                },
                                modifier = Modifier.size(54.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(0.dp),
                                enabled = !isCheckingWhatsApp
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_business_custom),
                                    contentDescription = "WhatsApp Business",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(54.dp)
                                )
                            }
                        }

                        if (!isNormalInstalled && !isBusinessInstalled) {
                            Button(
                                onClick = {
                                    val textMsg = getTemplateMsg()
                                    openWhatsAppChat(context, contact.primaryPhone, textMsg, contact.useWhatsAppBusiness)
                                },
                                modifier = Modifier.size(54.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom),
                                    contentDescription = "WhatsApp",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(54.dp)
                                )
                            }
                        }
                    }

                    if (contact.instagram.isNotEmpty()) {
                        val isInstagramButtonEnabled = !isCheckingInstagramOnLoad && instagramExists
                        Button(
                            onClick = {
                                openInstagramProfile(context, contact.instagram)
                                if (!contact.instagramFollowed) {
                                    onUpdateContact(contact.copy(instagramFollowed = true))
                                }
                            },
                            modifier = Modifier.size(54.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (instagramExists) Color(0xFFE1306C) else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (instagramExists) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ),
                            contentPadding = PaddingValues(0.dp),
                            enabled = isInstagramButtonEnabled
                        ) {
                            Icon(
                                imageVector = if (!instagramExists) Icons.Default.Block else Icons.Default.Person,
                                contentDescription = "Instagram",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
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
    icon: ImageVector? = null,
    painter: androidx.compose.ui.graphics.painter.Painter? = null,
    label: String,
    value: String,
    iconColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )
        }
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
    var useWhatsAppBusiness by remember { mutableStateOf(contact.useWhatsAppBusiness) }
    var instagramFollowed by remember { mutableStateOf(contact.instagramFollowed) }

    val editContext = LocalContext.current
    var whatsAppNormalExists by remember { mutableStateOf(false) }
    var whatsAppBusinessExists by remember { mutableStateOf(false) }

    val normalInstalled = remember(editContext) { isAppInstalled(editContext, "com.whatsapp") }
    val businessInstalled = remember(editContext) { isAppInstalled(editContext, "com.whatsapp.w4b") }

    LaunchedEffect(primaryPhone) {
        if (primaryPhone.isNotBlank()) {
            whatsAppNormalExists = checkIfWhatsAppRegistered(editContext, primaryPhone, isBusiness = false)
            whatsAppBusinessExists = checkIfWhatsAppRegistered(editContext, primaryPhone, isBusiness = true)
        }
    }

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
                    leadingIcon = { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(24.dp)) }
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

                // No WhatsApp Preference checklist in Edit screen as per user request

                if (instagram.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = instagramFollowed,
                            onCheckedChange = { instagramFollowed = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Já sigo este contato no Instagram",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (primaryPhone.isNotBlank() || instagram.isNotBlank()) {
                    Text(
                        text = "Ações Rápidas de Integração",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (primaryPhone.isNotBlank()) {
                            if (normalInstalled) {
                                Button(
                                    onClick = {
                                        openWhatsAppChat(editContext, primaryPhone, "", false)
                                    },
                                    modifier = Modifier.size(54.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                    contentPadding = PaddingValues(0.dp),
                                    enabled = whatsAppNormalExists
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_custom),
                                        contentDescription = "WhatsApp Padrão",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }

                            if (businessInstalled) {
                                Button(
                                    onClick = {
                                        openWhatsAppChat(editContext, primaryPhone, "", true)
                                    },
                                    modifier = Modifier.size(54.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                    contentPadding = PaddingValues(0.dp),
                                    enabled = whatsAppBusinessExists
                                ) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_whatsapp_business_custom),
                                        contentDescription = "WhatsApp Business",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(54.dp)
                                    )
                                }
                            }
                        }

                        if (instagram.isNotBlank()) {
                            Button(
                                onClick = {
                                    openInstagramProfile(editContext, instagram)
                                },
                                modifier = Modifier.size(54.dp),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = if (instagramFollowed) Icons.Default.OpenInNew else Icons.Default.Person,
                                    contentDescription = "Instagram",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel/Close Button: Only icon, elegant circular shape
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancelar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Save button: Only icon, elegant circular shape
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
                                        observations = observations,
                                        useWhatsAppBusiness = useWhatsAppBusiness,
                                        instagramFollowed = instagramFollowed
                                    )
                                )
                            }
                        },
                        modifier = Modifier.size(54.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Salvar",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
