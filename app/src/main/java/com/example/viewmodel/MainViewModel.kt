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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar

class MainViewModel(
    application: Application,
    private val repository: ContactRepository
) : AndroidViewModel(application) {

    // Contacts state flow from Room
    val contacts: StateFlow<List<ContactEntity>> = repository.allContacts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // User settings: Custom owner name (usuario_android)
    private val sharedPrefs = application.getSharedPreferences("extrai_cartao_prefs", Context.MODE_PRIVATE)
    var userName by mutableStateOf(sharedPrefs.getString("user_name", "Facundo") ?: "Facundo")
        private set

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
                scanError = null
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
                    } else {
                        throw Exception("Não foi possível decodificar os dados retornados no JSON.")
                    }
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

    // Save contact to modern Room database
    fun saveContact() {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = ContactEntity(
                name = parsedName,
                primaryPhone = parsedPrimaryPhone,
                secondaryPhone = parsedSecondaryPhone,
                address = parsedAddress,
                observations = parsedObservations,
                imageBase64 = capturedImageBase64 ?: ""
            )
            repository.insertContact(entity)
            withContext(Dispatchers.Main) {
                clearScannedState()
                showingManualAdd = false
            }
        }
    }

    // Save edited Contact back to database
    fun saveEditedContact(contact: ContactEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateContact(contact)
            withContext(Dispatchers.Main) {
                selectedContact = contact
                editModeActive = false
            }
        }
    }

    // Delete contact
    fun deleteContact(contact: ContactEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteContact(contact)
            withContext(Dispatchers.Main) {
                if (selectedContact?.id == contact.id) {
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
