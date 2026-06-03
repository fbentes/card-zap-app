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
import java.io.File

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
                var showFabMenu by remember { mutableStateOf(false) }

                Box(contentAlignment = Alignment.BottomEnd) {
                    FloatingActionButton(
                        onClick = { showFabMenu = !showFabMenu },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        AnimatedContent(
                            targetState = showFabMenu,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "fab_icon"
                        ) { isMenuOpen ->
                            if (isMenuOpen) {
                                Icon(Icons.Default.Close, "Fechar opções")
                            } else {
                                Icon(Icons.Default.Add, "Novo Cartão")
                            }
                        }
                    }

                    if (showFabMenu) {
                        Card(
                            modifier = Modifier
                                .padding(bottom = 72.dp)
                                .width(220.dp),
                            elevation = CardDefaults.cardElevation(8.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "Capturar Cartão",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    text = { Text("Tirar Foto com Câmera", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        showFabMenu = false
                                        triggerCamera()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Escolher da Galeria", fontSize = 14.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    onClick = {
                                        showFabMenu = false
                                        galleryLauncher.launch("image/*")
                                    }
                                )
                            }
                        }
                    }
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
                    onBack = { viewModel.clearScannedState() }
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
                    onSelectGallery = { galleryLauncher.launch("image/*") }
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var sendToWhatsApp by remember { mutableStateOf(true) }
    var useBusinessWhatsApp by remember { mutableStateOf(false) }
    var customMessage by remember { mutableStateOf("") }

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

        Button(
            onClick = { viewModel.analyzeCardImage() },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary
            ),
            enabled = !viewModel.isScanning,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Text(
                    text = if (viewModel.isScanning) "Analisando com IA..." else "Extrair Dados com IA",
                    fontWeight = FontWeight.Bold
                )
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
                        text = "A IA do Gemini está analisando o layout do cartão para extrair nome, telefones (detectando WhatsApp), endereço e serviços de forma inteligente...",
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = sendToWhatsApp,
                        onCheckedChange = { sendToWhatsApp = it }
                    )
                    Text(
                        text = "Enviar mensagem de apresentação ao salvar",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (sendToWhatsApp) {
                    Text(
                        text = "Selecione o canal de envio (Remetente):",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { useBusinessWhatsApp = false }
                        ) {
                            RadioButton(
                                selected = !useBusinessWhatsApp,
                                onClick = { useBusinessWhatsApp = false }
                            )
                            Text("WhatsApp Padrão", fontSize = 13.sp)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { useBusinessWhatsApp = true }
                        ) {
                            RadioButton(
                                selected = useBusinessWhatsApp,
                                onClick = { useBusinessWhatsApp = true }
                            )
                            Text("WhatsApp Business", fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Mensagem para Enviar (Personalize se desejar):",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp)
                    )

                    OutlinedTextField(
                        value = customMessage,
                        onValueChange = { customMessage = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 8,
                        minLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(8.dp),
                        supportingText = { Text("Lembrete: A variável de usuário Google foi configurada como $ {usuario_android} (definida pelo campo usuario_android).", fontSize = 10.sp) }
                    )
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
                        val shouldOpenWhatsApp = sendToWhatsApp
                        val isBusiness = useBusinessWhatsApp

                        viewModel.saveContact()
                        Toast.makeText(context, "Contato salvo com sucesso!", Toast.LENGTH_SHORT).show()

                        if (shouldOpenWhatsApp) {
                            if (phone.isNotBlank()) {
                                openWhatsAppChat(context, phone, msgToSubmit, isBusiness)
                            } else {
                                Toast.makeText(context, "Contato salvo, mas telefone principal está vazio para WhatsApp.", Toast.LENGTH_LONG).show()
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
    onSelectGallery: () -> Unit
) {
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

        if (contacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                    text = "Nenhum cartão escaneado ainda",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Alimente a IA capturando uma foto de um cartão físico ou carregando um arquivo de imagem da sua galeria para economizar digitação!",
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Tirar Foto", fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onSelectGallery,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Abrir Galeria", fontWeight = FontWeight.Bold)
                        }
                    }
                }
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
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar")
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
                        val formattedPhone = formatNumberToWhatsApp(contact.primaryPhone)
                        val textMsg = getTemplateMsg()
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://api.whatsapp.com/send?phone=$formattedPhone&text=${Uri.encode(textMsg)}")
                            )
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Não foi possível abrir o WhatsApp: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
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
