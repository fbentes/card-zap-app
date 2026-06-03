# Plano de Execução - App Extrai Cartão

Este plano detalha o desenvolvimento do aplicativo Android para extração de dados de cartões de visita usando Inteligência Artificial (Gemini API) e salvamento local.

## 1. Escopo das Funcionalidades

1. **Captura de Imagem do Cartão**:
   - Captura direta via Câmera do dispositivo.
   - Seleção de imagem existente na Galeria (essencial para teste rápido com imagens prontas).
2. **Análise de Imagem por IA (Gemini 3.5 Flash)**:
   - Envio da imagem codificada em Base64 para o modelo Gemini 3.5 Flash.
   - Extração estruturada (JSON) contendo:
     - Nome (ex: "abud - PNEUS e RODAS")
     - Telefone Principal (aquele que possui ícone do WhatsApp ou indica WhatsApp em texto)
     - Telefone Secundário (opcional)
     - Endereço (opcional)
     - Observações (opcional, agrupando serviços prestados)
3. **Persistência Local (Room Database)**:
   - Banco de dados SQLite local mapeando os contatos cadastrados.
   - Armazenamento da imagem original em Base64 para visualização offline.
4. **Gerenciamento de Usuário**:
   - Campo para cadastrar o nome do usuário do celular Android (`usuario_android`), salvo nas preferências.
5. **Integrações e WhatsApp**:
   - Envio automático de modelo de mensagem via WhatsApp após o registro:
     - Saudação dinâmica conforme a hora local (Bom dia / Boa tarde / Boa noite).
     - Corpo da mensagem: *"Aqui é o(a) [usuario_android]. Envio essa mensagem para registro e contato breve."*
   - Botão para exportar o contato diretamente para a lista nativa de contatos do Android (usando Intents do sistema).

## 2. Arquitetura e Componentes Técnicos

- **Interface do Usuário (UI)**: Jetpack Compose com Material Design 3.
- **Banco de Dados**: Room Database com Kotlin Coroutines e Flows para atualizações reativas na lista.
- **Networking**: Retrofit + Kotlin Serialization para chamada direta ao REST API do Gemini para assegurar autonomia no protótipo.
- **Gerenciamento de Estado**: MVVM (Model-View-ViewModel) mantendo a separação clara de responsabilidades.

## 3. Cronograma de Desenvolvimento

1. **Configuração de Dependências & Metadados**:
   - Sincronizar o `metadata.json` e definir o `applicationId` final.
   - Habilitar e configurar o Coil Image Loader para exibição de imagens.
2. **Implementação do Banco de Dados Local**:
   - Criar Entidade `Contact`, o correspondente `ContactDao` e o `AppDatabase`.
3. **Módulo de Integração com API do Gemini**:
   - Configurar o client Retrofit.
   - Implementar o prompt multimodal para extrair JSON formatado do cartão.
4. **Implementação da UI & Lógicas**:
   - Desenhar a tela inicial com listagem e botões de ação ("Tirar Foto" / "Galeria").
   - Adicionar o formulário de revisão de dados antes de salvar.
   - Adicionar o diálogo para gerenciar o nome do `usuario_android`.
   - Adicionar lógica de envio de mensagem de WhatsApp e exportação de contatos nativos.
5. **Validação & Testes**:
   - Compilação usando `compile_applet` para assegurar build sem erros.
