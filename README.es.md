# Aiphone (asistente telefónico con IA)

**Otros idiomas:** [日本語](README.md) · [English](README.en.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md) · [한국어](README.ko.md)

Aiphone es una aplicación experimental para Android cuyo objetivo es ayudar a preparar de antemano el propósito y los puntos clave de una llamada y permitir que una IA atienda parte de una llamada saliente iniciada por el usuario. También incluye una función experimental de traducción de voz bidireccional mediante Gemini API.

> **Software experimental en una fase inicial.** La interfaz y la usabilidad todavía no están pulidas. El comportamiento puede variar según el dispositivo y la aplicación de llamadas. No confíe únicamente en la IA para llamadas importantes relacionadas con contratos, dinero, asuntos médicos, organismos públicos u otras decisiones relevantes.

## Funciones

- Preparar el propósito y los puntos clave antes de llamar, y permitir que la IA responda durante una llamada saliente.
- Sesión de llamada en la que la IA responde según lo que diga la otra persona y el contexto de la conversación.
- Traducción de voz bidireccional con Gemini Live, con selección de idioma de origen y de destino.
- Guardar datos personales en el dispositivo y solicitar permiso al usuario antes de revelar determinados datos.
- Cambiar el idioma de la interfaz, incluidos inglés, chino simplificado y tradicional, coreano y español.

### Ideas futuras

- Atención de llamadas entrantes por IA
- Grabación de llamadas
- Transcripción de llamadas

Son ideas futuras, no una promesa de que estén disponibles en la versión actual. Este README no presenta la grabación ni la transcripción como funciones ya implementadas.

## Coste

Aiphone no tiene planes de pago ni compras integradas; sus funciones están pensadas para ser gratuitas. El uso de Gemini API está diseñado para la cuota gratuita y no depende de funciones exclusivas de pago.

Sin embargo, **Google controla la disponibilidad de la cuota gratuita, los modelos y los límites de Gemini API. No se garantiza que el servicio sea gratuito para siempre.** Consulte los precios y las condiciones actuales al crear o utilizar una clave API.

- [Crear una clave de Gemini API (Google AI Studio)](https://aistudio.google.com/app/apikey)
- [Condiciones adicionales de Gemini API](https://ai.google.dev/gemini-api/terms)

## Entorno probado

- Dispositivo probado: AQUOS sense6s (SH-RM19s)
- Sistema probado: Android 13
- `minSdk` es Android 11 (API 30), pero esto no significa que todos los dispositivos con Android 11 o posterior se hayan probado o sean compatibles.

Hasta ahora solo se ha probado físicamente en el dispositivo indicado. El procesamiento de audio del fabricante, las aplicaciones de llamadas y las restricciones del sistema operativo pueden provocar diferencias en otros dispositivos.

## Requisitos

1. **Una clave de Gemini API** (debe obtenerla por su cuenta).
2. Configuración de las opciones de desarrollador de Android para obtener permisos `shell` mediante depuración inalámbrica con **priv-kit**.
3. Un dispositivo Android compatible, una aplicación de llamadas adecuada y los permisos de Android necesarios para las funciones que utilice.

priv-kit es una biblioteca para iniciar y administrar procesos privilegiados de Android. Aiphone utiliza la depuración inalámbrica para conectarse a un puente que se ejecuta con permisos `shell`. Estos permisos son más amplios que los de una aplicación normal: autorice solo dispositivos y redes que conozca y en los que confíe.

- [Repositorio oficial de priv-kit](https://github.com/priv-kit/priv-kit)

## Configuración inicial

1. Active **Depuración inalámbrica** en las opciones de desarrollador de Android.
2. Abra la guía de configuración de priv-kit en los ajustes de Aiphone y siga los pasos de emparejamiento y conexión.
3. Introduzca su propia clave de Gemini API.
4. Introduzca sus datos personales, el propósito de la llamada, la información que debe comunicar o confirmar y los límites de decisión de la IA.
5. Conceda los permisos necesarios y active la función en la llamada saliente que quiera que atienda la IA.
6. Para traducir, seleccione su idioma y el de la otra persona en la pantalla de traducción antes de iniciar la función.

Los nombres de los ajustes y las pantallas de Android pueden variar según el dispositivo y la versión del sistema. Si falla la conexión, consulte la guía y el diagnóstico de la aplicación.

## Privacidad y seguridad

Lea el [aviso de privacidad](PRIVACY.es.md). El audio de las llamadas y el texto relacionado pueden enviarse a Gemini API. Según las condiciones de la cuota gratuita de Google, las entradas y salidas pueden utilizarse para mejorar los servicios de Google y pueden ser procesadas por revisores humanos. Extreme la precaución al utilizar la cuota gratuita en llamadas que contengan datos personales o confidenciales.

No utilice la aplicación para ocultar que participa una IA. El usuario es responsable de informar a la otra persona sobre la participación de la IA, obtener los consentimientos necesarios para procesar audio o grabar, tratar los datos personales conforme a la ley y comprobar las leyes y condiciones aplicables.

En algunas llamadas VoIP, la aplicación no puede determinar con fiabilidad la dirección de la llamada. Por seguridad, omitirá el inicio automático de la IA; confirme que se trata de una llamada saliente y active la función manualmente desde la aplicación.

## Compilación

1. Clone o descargue este repositorio.
2. Abra la carpeta raíz en Android Studio.
3. Cuando termine la sincronización de Gradle, ejecute `:app:assembleDebug`.

La compilación requiere conexión de red para descargar Android Gradle Plugin, Kotlin, AndroidX, Material Components, OkHttp, priv-kit y otras dependencias.

## Licencia

El código original de Aiphone se publica bajo la [Apache License 2.0](LICENSE). Apache-2.0 permite el uso comercial, la modificación y la redistribución si se cumplen sus condiciones. Al redistribuir, conserve la licencia y los avisos de copyright/atribución aplicables, e indique los archivos modificados como exige la licencia.

Las bibliotecas de terceros siguen sujetas a sus propias licencias. Consulte [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). En la fecha de revisión, el nivel raíz del repositorio de priv-kit no mostraba un archivo de licencia claro, por lo que sus condiciones deben confirmarse por separado. La licencia de Aiphone no sustituye las condiciones de los terceros.

## Descargo de responsabilidad

El software se proporciona «TAL CUAL», sin garantías de funcionamiento, precisión ni disponibilidad continua. La IA puede interpretar mal el habla, traducir incorrectamente o responder de forma inexacta. Verifique los resultados y no delegue decisiones importantes ni consentimientos únicamente en la IA sin supervisión.

Si la dirección de la llamada aparece como `UNKNOWN`, consulte la [guía de diagnóstico de llamadas VoIP](VOIP_DIAGNOSTICS.es.md).
