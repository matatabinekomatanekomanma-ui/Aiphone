# Diagnóstico de la dirección de llamadas VoIP

Use esta guía cuando una llamada saliente de WhatsApp, LINE, Rakuten Link u otra app VoIP se marque como `UNKNOWN`. Los nuevos registros recopilan señales que Android expone; no garantizan determinar la dirección si la app no la proporciona.

## Recopilar los registros

Conecte el dispositivo mediante ADB y ejecute:

```bash
adb logcat -c
adb logcat -v threadtime -s RLAudio:I '*:S'
```

Active el monitor de llamadas de Aiphone y realice llamadas salientes separadas desde WhatsApp, LINE y Rakuten Link. Mantenga cada llamada conectada al menos 10 segundos y después cuelgue. Guarde las líneas que incluyan `DIRECTION_DIAG`, `AudioManager mode changed`, `Telephony state` y `call ACTIVE`.

Los registros no incluyen intencionadamente audio de la llamada, números de teléfono, contactos ni claves API. Sin embargo, pueden incluir nombres de paquete/UID de apps VoIP compatibles, horas de uso recientes, importancia de procesos, formatos de audio y tipos de dispositivos. Revise los registros antes de compartirlos.

## Acceso de uso opcional

Para registrar eventos recientes de uso de apps, conceda a Aiphone el acceso especial **Acceso de uso** en los ajustes de Android. La ubicación varía; suele estar en Aplicaciones → Acceso especial de aplicaciones → Acceso de uso. Es opcional y no es necesario para el monitor de llamadas. Sin este acceso aparecerá `usageStatsAccess=false` y se omitirán esos eventos.

## Qué se registra

- Versión de Android, modo de AudioManager, estado de Telephony y permisos
- Configuraciones activas de grabación/reproducción y tipos de dispositivos de audio
- Apps VoIP compatibles instaladas y sus UID; importancia de procesos cuando Android la expone
- Eventos y marcas de tiempo de uso recientes si se concede el acceso
- Si Aiphone tiene marcado el inicio de una llamada saliente

## Limitación actual

`OutboundCallTracker.markAppInitiatedOutbound()` está pensado para ejecutarse justo antes de que Aiphone lance un Intent de llamada saliente. En el código actual no hay ningún lugar que invoque el método. Por eso, marcar directamente desde WhatsApp, LINE o Rakuten Link no establece la marca de llamada saliente. `AudioManager.MODE_IN_COMMUNICATION` indica audio de comunicación, pero por sí solo no distingue llamadas entrantes de salientes.
