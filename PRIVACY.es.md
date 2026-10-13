# Aviso de privacidad

**Otros idiomas:** [日本語](PRIVACY.md) · [English](PRIVACY.en.md) · [简体中文](PRIVACY.zh-CN.md) · [繁體中文](PRIVACY.zh-TW.md) · [한국어](PRIVACY.ko.md)

Última actualización: 2026-10-13

Aiphone es una aplicación experimental para Android que sigue en desarrollo. Utilícela bajo su propia responsabilidad y lea este aviso antes de activar las funciones de llamada o traducción.

## 1. Recopilación de datos por el desarrollador

El código fuente actual no incluye una función para enviar el contenido de las llamadas a un servidor propio del desarrollador de Aiphone ni integra un servicio de analítica de uso. Esto no significa que la aplicación no procese datos localmente: procesa y guarda el estado de las llamadas, el audio, los resúmenes introducidos por el usuario, los ajustes y los datos personales que necesita para funcionar. Cuando se utiliza Gemini API, los datos enviados a la API se entregan a Google.

## 2. Información que puede enviarse a Gemini API

Según la función de llamada o traducción de IA activada, se puede enviar:

- Audio capturado de la otra persona y/o del usuario durante una llamada
- Texto, instrucciones del sistema y ajustes de idioma necesarios para responder o traducir
- El propósito, resumen, puntos clave y otra información introducida por el usuario que la IA necesite
- Datos personales cuya divulgación haya autorizado el usuario
- La clave de Gemini API usada para autenticación e información técnica adjunta a las solicitudes

El diseño intenta incluir en las instrucciones generales solo si existe un campo de datos personales y solicitar autorización antes de obtener un valor concreto cuando sea necesario. No obstante, los datos personales escritos en resúmenes o prompts libres, u otro contenido enviado a una función de IA, también pueden transmitirse.

## 3. Advertencia importante sobre la cuota gratuita

Las condiciones adicionales de Gemini API de Google indican que las entradas y salidas de los servicios gratuitos pueden utilizarse para ofrecer, mejorar y desarrollar servicios de Google y tecnologías de aprendizaje automático, y que revisores humanos pueden revisar o procesar entradas y salidas para mejorar la calidad. Las condiciones también advierten que no se debe enviar información confidencial o personal a servicios gratuitos.

**Piense detenidamente antes de enviar audio real de llamadas, nombres, números de teléfono, direcciones, información médica, contratos, datos de autenticación o información empresarial confidencial usando la cuota gratuita.** Aiphone no puede cambiar ni controlar las prácticas de datos de Google. Las condiciones pueden cambiar; consulte la versión vigente antes de usar el servicio.

- [Condiciones adicionales de Gemini API](https://ai.google.dev/gemini-api/terms)

## 4. Datos almacenados en el dispositivo

- La clave de Gemini API, los ajustes, los datos personales y los resúmenes de llamadas se guardan en el almacenamiento privado de la aplicación Android.
- La clave de Gemini API, los datos personales y los resúmenes de llamadas guardados se cifran con una clave AES-GCM de Android Keystore antes de almacenarse en el espacio privado de la aplicación. Si falla el cifrado de datos nuevos, se cancela el guardado en lugar de recurrir al texto sin cifrar.
- Los datos guardados en texto sin cifrar por versiones anteriores se intentan migrar al almacenamiento cifrado al leerlos. Si la migración falla, no se sobrescribe el valor original, por lo que puede quedar texto sin cifrar de versiones antiguas en el dispositivo. Si no se pueden descifrar datos personales existentes, el guardado se bloquea mientras el campo correspondiente esté vacío. Vuelva a introducir ese campo para reemplazar explícitamente el valor ilegible. Así se evita que los campos vacíos de la interfaz borren datos existentes. Si no se pueden descifrar los resúmenes de llamadas guardados, se bloquean la edición y la eliminación para no sobrescribir los datos originales.
- Algunos ajustes, como el prompt del sistema editable por el usuario y la frase de prueba, no se cifran con el mismo mecanismo. No introduzca secretos ni datos personales innecesarios en ellos.
- Los registros de diagnóstico normales están diseñados para no incluir transcripciones de llamadas, texto traducido ni contenido de respuestas de herramientas. Sin embargo, no se puede garantizar la eliminación completa de datos sensibles de todos los registros de la aplicación y de Android.
- La versión actual no ofrece un archivo específico de grabaciones ni un archivo de transcripciones. Sin embargo, el audio puede transmitirse en flujo a Gemini API mientras una función de IA está activa.

Antes de entregar el dispositivo a otra persona, revise los datos y las claves guardados y borre los datos de la aplicación si procede. Desinstalarla suele borrar los datos privados de la app, pero no garantiza que se eliminen todas las copias de seguridad o del sistema.

### Registros de diagnóstico de la dirección de llamada

Cuando no se puede determinar la dirección de una llamada, los registros de diagnóstico pueden incluir el modo de AudioManager, configuraciones de grabación/reproducción de audio, tipos de dispositivos de audio, nombres de paquetes y UID de aplicaciones VoIP compatibles e información visible de procesos. Si el usuario concede el acceso de uso de Android, también pueden registrarse eventos recientes y marcas de tiempo de esas aplicaciones VoIP. Estos registros no incluyen intencionadamente contenido de audio, números de teléfono, contactos ni claves API. Permanecen en Android Logcat y pueden quedar expuestos si se recopilan o comparten mediante ADB; revíselos antes de compartirlos.

## 5. priv-kit y depuración inalámbrica

La aplicación utiliza priv-kit y la depuración inalámbrica de Android para conectarse a un puente que se ejecuta en un proceso privilegiado con permisos `shell`. Esto se usa para funciones como el encaminamiento del audio de llamadas y proporciona más acceso que una aplicación Android normal. Configúrelo solo en dispositivos de confianza y gestione cuidadosamente el emparejamiento y el acceso a la red.

## 6. Servicios de terceros y responsabilidad del usuario

Google Gemini API, Android, las aplicaciones de llamadas, priv-kit y otras bibliotecas o servicios tienen sus propias condiciones y prácticas de privacidad. Aiphone no controla el tratamiento ni la retención de sus datos.

La IA puede interpretar mal, traducir incorrectamente o responder de forma inexacta. El usuario es responsable de informar a la otra persona, obtener el consentimiento cuando sea necesario, tratar los datos personales legalmente, verificar la legalidad de la llamada y tomar las decisiones finales sobre contratos o dinero. No deje las llamadas importantes completamente en manos de la IA.

## 7. Cambios

Este aviso puede actualizarse si cambian las funciones o los servicios de terceros. Consulte la versión más reciente del repositorio.
