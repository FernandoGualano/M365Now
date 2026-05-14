# M365 Now - Android MVP v1

Aplicación Android nativa inspirada en el prototipo aprobado de M365 Now.

## Alcance v1

- Home con métricas, categorías y últimas novedades.
- Artículos provenientes de fuentes RSS/Atom configurables.
- Roadmap separado de las categorías RSS, consumiendo el endpoint público de Microsoft 365 Roadmap.
- Favoritos.
- Fuentes RSS: agregar, editar, probar, habilitar/deshabilitar y borrar con confirmación.
- Ajustes básicos con cambio claro/oscuro.
- Acerca de / disclaimer.
- Cache local con Room.
- Sin login.
- Sin backend propio.

## Categorías de artículos

Las categorías de artículos son internas de la app y no dependen del Roadmap:

- Microsoft 365
- Exchange
- Teams / UC
- Entra ID
- Intune
- Security
- Copilot
- Azure
- OneDrive / SPO
- MS Graph / PS
- Community

## Roadmap

La sección Roadmap está separada de las fuentes RSS. Consume:

```text
https://www.microsoft.com/releasecommunications/api/v1/m365
```

Los productos del Roadmap se muestran de acuerdo con lo informado por Microsoft en el payload del endpoint.

## Cómo compilar

1. Abrir esta carpeta con Android Studio.
2. Esperar a que Gradle sincronice dependencias.
3. Ejecutar:

```bash
./gradlew assembleDebug
```

4. El APK debug quedará en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notas importantes

- Este proyecto usa íconos de Material Icons y emojis/placeholders para categorías. Para una publicación real se recomienda incorporar assets locales definitivos y revisar condiciones de uso de logos/marcas de Microsoft.
- Las URLs RSS de Microsoft Tech Community pueden cambiar. La app permite editar fuentes desde la UI.
- WorkManager/notificaciones quedan para v1.1.

## Próximos pasos sugeridos v1.1

- Sincronización automática con WorkManager.
- Notificaciones por categoría/fuente.
- Importar/exportar configuración de fuentes.
- Detalle completo de artículos con WebView o Custom Tabs.
- Persistir preferencia de tema.
- Mejorar parser de RSS/Atom para contenido enriquecido.


## v1.0.2
- Corrección crítica: las llamadas HTTP ahora corren en `Dispatchers.IO` para evitar `NetworkOnMainThreadException`.
- Mejoras menores en mensajes de error de sincronización.
