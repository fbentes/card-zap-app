package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.api.*
import com.example.data.ContactEntity
import com.example.data.ContactRepository
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import com.example.utils.ContactSystemSync
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar

class MainViewModel(
    application: Application,
    private val repository: ContactRepository
) : AndroidViewModel(application) {

    // Contacts state flow from native Android phonebook / Room fallback
    private val _contactsList = MutableStateFlow<List<ContactEntity>>(emptyList())
    val contacts: StateFlow<List<ContactEntity>> = _contactsList.asStateFlow()

    // User settings: Custom owner name (usuario_android)
    private val sharedPrefs = application.getSharedPreferences("extrai_cartao_prefs", Context.MODE_PRIVATE)
    var userName by mutableStateOf("")
        private set

    init {
        // Pre-populate with retrieved Google account username, or default to facbentes
        val savedName = sharedPrefs.getString("user_name", null)
        if (savedName == null) {
            val googleName = getGoogleAccountLabel(application)
            userName = googleName.ifEmpty { "facbentes" }
            sharedPrefs.edit().putString("user_name", userName).apply()
        } else {
            userName = savedName
        }
        
        // Initial load and sync/migration of existing contacts!
        refreshContactsList()
    }

    private fun getGoogleAccountLabel(context: Context): String {
        try {
            val am = android.accounts.AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            if (accounts.isNotEmpty()) {
                val email = accounts[0].name
                if (email.contains("@")) {
                    return email.substringBefore("@")
                }
                return email
            }
        } catch (e: SecurityException) {
            // Permission checked at runtime or not granted yet
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "facbentes" // Personalized default fallback based on metadata info
    }

    // Scan operations state
    var isScanning by mutableStateOf(false)
        private set
    var scanError by mutableStateOf<String?>(null)
        private set
    var capturedImageBase64 by mutableStateOf<String?>(null)
        private set

    // Extracted Fields State (for preview & editing BEFORE saving)
    var parsedName by mutableStateOf("")
    var parsedPrimaryPhone by mutableStateOf("")
    var parsedSecondaryPhone by mutableStateOf("")
    var parsedAddress by mutableStateOf("")
    var parsedObservations by mutableStateOf("")
    var parsedInstagram by mutableStateOf("")

    // List of states for viewing/editing saved contacts
    var showingManualAdd by mutableStateOf(false)
    var selectedContact by mutableStateOf<ContactEntity?>(null)
    var editModeActive by mutableStateOf(false)

    fun updateUserName(name: String) {
        userName = name
        sharedPrefs.edit().putString("user_name", name).apply()
    }

    // Set captured image and crop/resize it to be efficient to send to Gemini & store in DB
    fun processSelectedImage(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val resized = resizeBitmap(bitmap, 800) // target max 800px width/height for fast response and smooth storage
            val base64 = bitmapToBase64(resized)
            withContext(Dispatchers.Main) {
                capturedImageBase64 = base64
                // Clear previous results upon capturing new image
                parsedName = ""
                parsedPrimaryPhone = ""
                parsedSecondaryPhone = ""
                parsedAddress = ""
                parsedObservations = ""
                parsedInstagram = ""
                scanError = null
                // Directly trigger Gemini extraction on captured image
                analyzeCardImage()
            }
        }
    }

    fun clearScannedState() {
        capturedImageBase64 = null
        parsedName = ""
        parsedPrimaryPhone = ""
        parsedSecondaryPhone = ""
        parsedAddress = ""
        parsedObservations = ""
        parsedInstagram = ""
        scanError = null
        isScanning = false
    }

    // Call Gemini API to analyze the card image
    fun analyzeCardImage() {
        val base64Image = capturedImageBase64 ?: return
        isScanning = true
        scanError = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure internet Permission warning is verified
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey == "MY_GEMINI_API_KEY" || apiKey.isEmpty()) {
                    throw IllegalStateException("API Key do Gemini não está configurada no painel de Secrets!")
                }

                val prompt = """
                    Você é um assistente especialista em extrair dados estruturados de cartões de visita, cartões empresariais, de prestação de serviços ou de vendedores de forma precisa.
                    Analise a imagem em anexo deste cartão de visita de forma minuciosa e retorne os campos em formato JSON, conforme as propriedades abaixo:
                    - name: O nome do contato ou nome fantasia da empresa (exemplo: 'abud - PNEUS e RODAS'). O nome deve destacar o título de destaque principal do cartão.
                    - primaryPhone: O telefone principal do contato. ATENÇÃO extrema: o telefone principal SEMPRE será o telefone correspondente que possui o ícone do WhatsApp ao lado (ícone de mensagem/telefone verde do WhatsApp) ou o texto 'WhatsApp'/'Whats' do lado do número. Mantenha os números limpos ou no formato de telefone com DDD, por exemplo: '(22) 98858-1098'. Garanta que ele não fique vazio se houver telefone marcado com WhatsApp.
                    - secondaryPhone: Outro telefone presente no cartão (como fixo ou outro celular), se houver. Se não houver, deixe-o em branco/nulo.
                    - address: O endereço completo constante no cartão (rua, avenida, lote, quadra, bairro, cidade, estado). Se não houver, deixe-o em branco/nulo.
                    - observations: Os serviços prestados listados no cartão de forma estruturada (exemplo: 'Balanceamento, Alinhamento, Cambagem, Freio, Suspensão, Reforma de Rodas, Polimento de Rodas, Desempeno') ou observações principais. Se não houver, deixe-o em branco/nulo.
                    - instagram: O contato de Instagram (@usuario ou perfil/handle do Instagram) presente no cartão, se houver. Se não houver, preencha como string vazia "" ou nulo.

                    Regras cruciais:
                    1. Retorne APENAS o JSON válido. Não coloque nenhum bloco explicativo, markdown ```json ou introdução comercial. Retorne o JSON diretamente que coincida exatamente com a estrutura de classe desejada.
                    2. Se o campo for ausente no cartão, preencha como string vazia "" ou null no JSON.
                """.trimIndent()

                val inlineData = InlineData(mimeType = "image/jpeg", data = base64Image)
                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(parts = listOf(Part(text = prompt), Part(inlineData = inlineData)))
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json"
                    )
                )

                val response = RetrofitClient.apiService.generateContent(apiKey, request)
                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                if (responseText == null) {
                    throw Exception("A resposta da IA veio vazia.")
                }

                // Parse using Moshi
                val adapter = RetrofitClient.moshiInstance.adapter(ParsedContact::class.java)
                val parsed = adapter.fromJson(responseText)

                withContext(Dispatchers.Main) {
                    if (parsed != null) {
                        parsedName = parsed.name ?: ""
                        parsedPrimaryPhone = parsed.primaryPhone ?: ""
                        parsedSecondaryPhone = parsed.secondaryPhone ?: ""
                        parsedAddress = parsed.address ?: ""
                        parsedObservations = parsed.observations ?: ""
                        parsedInstagram = parsed.instagram ?: ""
                    } else {
                        throw Exception("Não foi possível decodificar os dados retornados no JSON.")
                    }
                    isScanning = false
                }

            } catch (e: retrofit2.HttpException) {
                val errorBody = try {
                    e.response()?.errorBody()?.string()
                } catch (ex: Exception) {
                    null
                }
                val errorMessage = if (!errorBody.isNullOrBlank()) {
                    try {
                        val json = org.json.JSONObject(errorBody)
                        val errorObj = json.optJSONObject("error")
                        errorObj?.optString("message") ?: errorBody
                    } catch (ex: Exception) {
                        errorBody
                    }
                } else {
                    e.message()
                }
                withContext(Dispatchers.Main) {
                    scanError = "Falha ao escanear o cartão: HTTP ${e.code()} - $errorMessage"
                    isScanning = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scanError = "Falha ao escanear o cartão: ${e.localizedMessage ?: e.message}"
                    isScanning = false
                }
            }
        }
    }

    // Read directly from Contacts system or fallback to cached Room database
    fun refreshContactsList() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (ContactSystemSync.hasContactsPermissions(context)) {
                try {
                    // Try to migrate any local Room database contacts that are not yet in AndroidContacts
                    // This solves: "pegar os dados já existentes no apk instalado (antes de sobrepor) e salvar na minha lista"
                    val localContacts = repository.allContacts.first()
                    if (localContacts.isNotEmpty()) {
                        ContactSystemSync.migrateRoomContactsToSystem(context, localContacts)
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Migration from Room to system contacts failed: ${e.message}", e)
                }

                // Query our tagged system contacts from Android Contacts ContentProvider
                try {
                    val systemList = ContactSystemSync.fetchSystemContacts(context)
                    _contactsList.value = systemList
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to query system contacts: ${e.message}", e)
                    // If content provider query fails, fallback to Room database!
                    repository.allContacts.collect { roomList ->
                        _contactsList.value = roomList
                    }
                }
            } else {
                // Permissions not granted yet. Falling back to displaying Room contents.
                repository.allContacts.collect { roomList ->
                    _contactsList.value = roomList
                }
            }
        }
    }

    // Save contact to modern Room database and system Contacts
    fun saveContact() {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = ContactEntity(
                name = parsedName,
                primaryPhone = parsedPrimaryPhone,
                secondaryPhone = parsedSecondaryPhone,
                address = parsedAddress,
                observations = parsedObservations,
                imageBase64 = capturedImageBase64 ?: "",
                instagram = parsedInstagram
            )
            // Insert in SQLite/Room local database
            repository.insertContact(entity)
            
            // Insert into native Android system contacts
            val context = getApplication<Application>()
            if (ContactSystemSync.hasContactsPermissions(context)) {
                ContactSystemSync.insertSystemContact(
                    context = context,
                    name = entity.name,
                    primaryPhone = entity.primaryPhone,
                    secondaryPhone = entity.secondaryPhone,
                    address = entity.address,
                    instagram = entity.instagram,
                    observations = entity.observations,
                    imageBase64 = entity.imageBase64
                )
            }
            
            // Reload contacts listing (from native Android contacts list)
            refreshContactsList()
            
            withContext(Dispatchers.Main) {
                clearScannedState()
                showingManualAdd = false
            }
        }
    }

    // Save edited Contact back to database and system contacts
    fun saveEditedContact(contact: ContactEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (ContactSystemSync.hasContactsPermissions(context)) {
                // If it is a system contact, delete older raw contact copy
                if (contact.id > 0) {
                    ContactSystemSync.deleteSystemContact(context, contact.id)
                }
                
                // Re-insert as a fresh Raw contact with updated values
                ContactSystemSync.insertSystemContact(
                    context = context,
                    name = contact.name,
                    primaryPhone = contact.primaryPhone,
                    secondaryPhone = contact.secondaryPhone,
                    address = contact.address,
                    instagram = contact.instagram,
                    observations = contact.observations,
                    imageBase64 = contact.imageBase64
                )
                
                // Also update Room Database
                try {
                    val localMatches = repository.allContacts.first().filter { 
                        it.primaryPhone == contact.primaryPhone || it.name == contact.name 
                    }
                    if (localMatches.isNotEmpty()) {
                        for (match in localMatches) {
                            repository.updateContact(contact.copy(id = match.id))
                        }
                    } else {
                        repository.insertContact(contact.copy(id = 0))
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to keep Room edit in sync: ${e.message}")
                }
            } else {
                repository.updateContact(contact)
            }
            
            refreshContactsList()
            
            withContext(Dispatchers.Main) {
                selectedContact = contact
                editModeActive = false
            }
        }
    }

    // Delete contact from database and system contacts
    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (ContactSystemSync.hasContactsPermissions(context)) {
                if (contact.id > 0) {
                    ContactSystemSync.deleteSystemContact(context, contact.id)
                }
                
                // Also remove matching rows in Room so they don't get re-migrated
                try {
                    val localMatches = repository.allContacts.first().filter {
                        it.primaryPhone == contact.primaryPhone || it.name == contact.name
                    }
                    for (match in localMatches) {
                        repository.deleteContact(match)
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to remove Room counterparts: ${e.message}")
                }
            } else {
                repository.deleteContact(contact)
            }
            
            refreshContactsList()
            
            withContext(Dispatchers.Main) {
                if (selectedContact?.id == contact.id || 
                    (selectedContact?.name == contact.name && selectedContact?.primaryPhone == contact.primaryPhone)) {
                    selectedContact = null
                    editModeActive = false
                }
            }
        }
    }

    // Get Greeting Message Line 1
    fun getGreetingMessage(): String {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        
        // Define according to direct requirements rules:
        // Se a hora for entre 00:01 e 11:59 -> saudacoes = Bom dia.
        // Se a hora for entre 12:00 e 17:59 -> saudacoes = Boa tarde.
        // Se a hora for entre 18:00 e 23:59 -> saudacoes = Boa noite.
        // What about 00:00? Let's treat it as Bom dia or Boa noite.
        
        return if (hour == 0 && minute == 0) {
            "Boa noite."
        } else if ((hour == 0 && minute > 0) || (hour in 1..11)) {
            "Bom dia."
        } else if (hour in 12..17) {
            "Boa tarde."
        } else {
            "Boa noite."
        }
    }

    // Get WhatsApp Message Template
    fun getWhatsAppMessage(): String {
        val greeting = getGreetingMessage()
        return "$greeting\nAqui é o(a) $userName . Envio essa mensagem para registro e contato breve."
    }

    // Helper functions for image manipulation
    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val aspectRatio = width.toFloat() / height.toFloat()
        
        val targetWidth: Int
        val targetHeight: Int
        
        if (width > height) {
            targetWidth = if (width > maxDimension) maxDimension else width
            targetHeight = (targetWidth / aspectRatio).toInt()
        } else {
            targetHeight = if (height > maxDimension) maxDimension else height
            targetWidth = (targetHeight * aspectRatio).toInt()
        }
        
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

class MainViewModelFactory(
    private val application: Application,
    private val repository: ContactRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
