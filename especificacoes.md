# Documento de Especificações Técnicas - Extrai Cartão

Este documento apresenta as especificações completas de funcionamento e arquitetura do aplicativo **Extrai Cartão**, projetado para automatizar a captura de dados de cartões de visita e agilizar o contato direto via WhatsApp.

---

## 1. Visão Geral do Sistema

O **Extrai Cartão** é uma ferramenta de produtividade para profissionais em campo, vendedores e prestadores de serviço. O aplicativo automatiza um fluxo de trabalho moroso:
1. Recebimento ou visualização de um cartão de visita físico.
2. Digitação manual dos campos de contato no smartphone.
3. Envio de uma mensagem de introdução no WhatsApp para registrar o contato.

Através do uso de visão computacional e inteligência artificial generativa multimodal (Gemini 3.5 Flash), o aplicativo resolve essa dor ao capturar/ler o cartão, extrair todos os dados estruturados instantaneamente, salvar localmente, permitir exportação para a agenda interna do Android e disparar uma mensagem pré-formatada direta para o WhatsApp do contato.

---

## 2. Requisitos Funcionais (RF)

### RF01 - Captura Multimodal de Cartões
O usuário deve poder alimentar o sistema de duas formas:
- **Câmera**: Capturar uma nova fotografia em tempo real.
- **Galeria**: Selecionar uma foto de cartão salva de maneira prévia no álbum de fotos (essencial para agilidade de demonstração e testes).

### RF02 - Extração de Dados Inteligente por IA
O sistema deve enviar a imagem para a API do Gemini com instruções detalhadas para extrair os seguintes campos:
- **Nome do Contato ou Nome Fantasia**: Priorizando o elemento principal ou comercial.
- **Telefone Principal**: O telefone que tiver indicativo de WhatsApp (ícone verde de telefone/mensagem ao lado ou a palavra "WhatsApp").
- **Telefone Secundário**: Outro celular ou telefone fixo listado, caso exista.
- **Endereço**: Endereço comercial completo listado no cartão.
- **Observações**: Descrição condensada dos serviços oferecidos ou slogans presentes no cartão de maneira resumida.

### RF03 - Tratamento de Campos Inexistentes
Se o cartão analisado não contiver endereço, telefone secundário ou observações, o aplicativo deve deixar os respectivos campos vazios de forma automática, permitindo edição manual.

### RF04 - Tela de Confirmação e Edição
Antes de salvar os resultados analisados pela IA no banco de dados local, os dados estruturados devem ser exibidos ao usuário em um formulário intuitivo, garantindo que o usuário possa corrigir qualquer dado impreciso ou alterar valores.

### RF05 - Persistência Local (Offline-First)
Os contatos escaneados (incluindo metadados e imagem capturada comprimida) devem ser mantidos localmente no dispositivo em um banco de dados Room SQLite, não dependendo de conexão futura com servidores para consulta.

### RF06 - Cadastro do Usuário Proprietário (usuario_android)
O proprietário deve poder configurar seu próprio nome em um menu/preferências, para que as mensagens geradas tragam a identificação correta automaticamente.

### RF07 - Modelo de Mensagem de Introdução (WhatsApp)
Após salvar o contato, o aplicativo deve gerar e permitir enviar uma mensagem via WhatsApp no seguinte formato:
- **Lógica de Saudação**:
  - `00:01` a `11:59` -> *"Bom dia."*
  - `12:00` a `17:59` -> *"Boa tarde."*
  - `18:00` a `23:59` -> *"Boa noite."*
- **Lógica do Corpo**:
  - *"Aqui é o(a) [usuario_android]. Envio essa mensagem para registro e contato breve."*
- **Execução**:
  - Abrir diretamente o WhatsApp por meio de Deep Link (`Intent.ACTION_VIEW`), pré-carregando a mensagem no chat específico sem precisar cadastrar o contato na lista telefônica previamente.

### RF08 - Sincronização com Contatos Nativos
O usuário deve ter uma opção direta para exportar o registro local para a agenda de contatos padrão do Android (através do `ContactsContract` através de Intents de inserção nativos).

---

## 3. Requisitos Não Funcionais (RNF)

### RNF01 - Velocidade e Tempo de Resposta
A análise de imagem do cartão de visita e o retorno estruturado via API do Gemini 3.5 Flash devem ocorrer, sob conexão celular ou Wi-Fi estável, em menos de 5 segundos.

### RNF02 - Design de UI Moderno (Material You)
A aplicação deve ser desenvolvida em Jetpack Compose, utilizando as diretrizes do Material Design 3, com suporte a transições suaves e design que priorize a usabilidade para uso com uma só mão.

### RNF03 - Segurança dos Dados do Proprietário
Diferente de contatos em nuvem proprietária, os dados armazenados não devem ser transmitidos a servidores terceiros além da inferência segura na API do Gemini. As chaves de serviço ficam protegidas em arquivos `.env` injetados dinamicamente via Gradle, não permanecendo expostas em código.

---

## 4. Arquitetura da Solução

O sistema adota o padrão recomendado para desenvolvimento Android moderno:

```
───────────────────────────────────────────────────────────────────────
|                     INTERFACE COM JOVEM COMPOSE (UI)                 |
───────────────────────────────────────────────────────────────────────
                                  │
                       Observa estado via Flow
                                  ▼
───────────────────────────────────────────────────────────────────────
|                             VIEWMODEL                               |
───────────────────────────────────────────────────────────────────────
            │                                             │
   Salva e consulta contatos                    Realiza análise multimodal
            ▼                                             ▼
────────────────────────────────────           ────────────────────────
|       REPOSITORY / DAO (ROOM)     |           |  GEMINI SERVICE (API) |
────────────────────────────────────           ────────────────────────
```

### Tecnologias-Chave:
- **Linguagem**: Kotlin 2.2+
- **Compilação**: SDK 36, compatível do Android Oreo (minSdk 24) ao Android 11-15+
- **Interface**: Jetpack Compose Com Material 3
- **Injeção de IA**: Base64 JPEG Multiplatform REST request enviada ao modelo `gemini-3.5-flash`.
