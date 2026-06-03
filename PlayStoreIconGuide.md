# CardZap - Guia de Visual e Publicação na Play Store

Este guia foi elaborado para auxiliar na distribuição e publicação do **CardZap** com a melhor apresentação visual profissional de acordo com as especificações da Google Play Store.

---

## 🎨 Logotipo & Identidade Visual (ZC)
O logotipo principal é definido pelo emblema composto pelas letras **"Z"** (Zap) e **"C"** (Card) harmonizadas graficamente em um gradiente verde-limão vibrante com fundo preto fosco de alta sofisticação.

---

## 📱 Ícone de Alta Resolução para a Google Play Store

Para publicar o aplicativo futuramente na Google Play Store, você precisará carregar um ícone principal com as seguintes especificações obrigatórias:

- **Dimensão:** `512px x 512px`
- **Formato:** `PNG de 32 bits` (com canal Alpha transparente ou cor de fundo integrada comercial)
- **Espaço de Cor:** `sRGB`
- **Tamanho Máximo do Arquivo:** `1024 KB`
- **Formato Visual:** Quadrado completo (a Play Store arredonda os cantos dinamicamente no catálogo para um visual uniforme).

### Localização do Asset no Código Fonte:
Nós configuramos e compilamos o aplicativo usando o ícone com acabamento premium. A imagem matriz original de alta resolução da marca está salva no diretório de recursos da aplicação:
📁 **`/app/src/main/res/drawable/ic_scanner_brand_1780509416779.png`**

**Como Extrair para Publicação:**
1. Você pode baixar diretamente esta imagem ou exportar o projeto ZIP do CardZap no menu superior do Google AI Studio.
2. A imagem `ic_scanner_brand_1780509416779.png` é a matriz em alta definição contendo o logotipo **"ZC"** com os tons de verde neon, perfeitamente adequada para upload no console de desenvolvedor do Google Play!

---

## 🎨 Diretrizes Adicionais do Material You (Adaptive Icons)

O aplicativo já está configurado com suporte completo a **Ícones Adaptativos** no Android 8.0+:
- **Camada de Fundo (Background):** Gradiente premium Dark Slate (`#FF0F172A`) em `/app/src/main/res/drawable/ic_launcher_background.xml`.
- **Camada de Frente (Foreground):** Centraliza de forma estrita o logotipo "ZC" em uma zona de segurança de `66dp` (evitando cortes dinâmicos em launchers Samsung, Pixel e Xiaomi) em `/app/src/main/res/drawable/ic_launcher_foreground.xml`.

Isso garante que ao instalar o aplicativo no celular, ele exiba o melhor ícone com compatibilidade de formato moderno (círculo, quadrado, lágrima ou esquilo)!
