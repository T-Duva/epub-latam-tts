# EPUB Latam TTS

App Android personal para **importar un EPUB** y **escucharlo en voz alta con acento español latino**.

## Qué hace

- Importás un `.epub` desde el teléfono
- Parsea capítulos y texto
- Lee en voz alta:
  - **Principal:** Google Cloud Text-to-Speech (`es-US-Neural2-A`, español latino)
  - **Respaldo:** TTS del sistema forzado a `es-MX` (nunca `es-ES`)
- Controles: play / pausa / stop / capítulo anterior-siguiente / velocidad

## Requisitos

- Android 8.0+ (API 26)
- Android Studio Ladybug+ o JDK 17
- (Opcional) API key de [Google Cloud Text-to-Speech](https://cloud.google.com/text-to-speech)

## Configuración

1. Copiá `local.properties.example` a `local.properties`
2. Ajustá `sdk.dir` a tu Android SDK
3. (Recomendado) Poné tu key:

```properties
GCS_TTS_API_KEY=tu_api_key
```

Sin key, la app funciona solo con el TTS del sistema (instalá **Español México** en Ajustes → Texto a voz).

## Build

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Uso

1. Abrí la app → **+**
2. Elegí un EPUB **sin DRM**
3. Abrí el libro → **Play**

## Notas

- Uso personal; no publiques el APK con tu API key embebida
- `local.properties` no se sube a git
- EPUBs con DRM no están soportados
