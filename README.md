# MajoApp: WebView + ticketera Bluetooth 58 mm

Esta entrega integra los tickets en la misma app que veníamos construyendo.
Paquete y applicationId: `com.majocafe.majoapp`. La actividad inicial continúa
siendo MainActivity con majocafeteria.com, splash y sin menú ni barra superior.
No se instala MajoTicket ni se usa una segunda app de impresión.

## Instalar en tu proyecto de Android Studio

1. Guarda una copia de tu proyecto actual. Copia el contenido de la carpeta
   MajoApp de este ZIP sobre la raíz de ese proyecto.
2. Conserva `local.properties`, `gradlew`, `gradlew.bat` y
   `gradle/wrapper/gradle-wrapper.jar` de tu proyecto existente. Estos tres
   archivos del wrapper no están en esta entrega. El wrapper se configura a
   Gradle 8.7; AGP es 8.6.0, JDK 17 y compileSdk 35.
3. El código Kotlin válido está en `app/src/main/java/com/majocafe/majoapp/`.
   Si mezclaste la entrega MajoTicket, elimina del proyecto sus archivos
   antiguos `com/majocafe/ticketera/` y su WebActivity. Si cambiaste el paquete
   a `com/majocafeteria/app/`, guarda esos archivos aparte y retira esa carpeta
   del código que compila este proyecto. No deben coexistir sus MainActivity
   anteriores con los archivos nuevos. También retira `ui/theme` de Compose
   si quedó de la plantilla: esta base usa XML.
4. Sincroniza Gradle y ejecuta la app. La dependencia de splash sigue incluida:
   `androidx.core:core-splashscreen:1.0.1`.

Incluye la versión de majo4 que compartiste, centrada y con escala 0.95, fondo
majo_coffee para splash/carga y la barra superior de progreso de 3 dp. Si has
personalizado más colores, logo o distancia de recarga en tu equipo, conserva
esos valores. Las funciones anteriores de PDF, alertas sin título, cookies,
Atrás y tratamiento del teclado se mantienen.

## Elegir la ticketera y probar

1. Empareja la ticketera desde los ajustes Bluetooth de Android con el PIN de
   su fabricante. La app lista equipos ya emparejados, no realiza búsqueda activa.
2. Al abrir MajoApp sin ticketera seleccionada, se abre una pantalla de
   configuración. Pulsa Seleccionar ticketera. En Android 12+ concede el permiso
   Dispositivos cercanos. No se necesita permiso de ubicación para este flujo.
3. Elige tu ticketera por nombre o dirección. No se selecciona el primer
   dispositivo automáticamente. La lista también puede contener equipos que
   no son impresoras.
4. Pulsa Imprimir prueba o Imprimir pedido de ejemplo y luego Volver a la web.
   La selección queda guardada. Los siguientes arranques van directamente a la web.

La configuración es una pantalla auxiliar dentro de MajoApp, no un menú fijo
encima de la web. Puedes volver a abrirla desde un botón de tu web, como se
explica a continuación. Se cierra con Volver a la web o Atrás.

## Conectar los botones de tu web

Copia `web/majo-printer.js` a tu servidor e inclúyelo **una vez en la página
principal**, por ejemplo:

```html
<script src="/assets/js/majo-printer.js"></script>
<button onclick="seleccionarTicketera().catch(e => alert(e.message))">Seleccionar ticketera</button>
<button onclick="imprimirPrueba().catch(e => alert(e.message))">Imprimir prueba</button>
```

Una vez que tengas el objeto JSON del pedido que enviaste:

```javascript
async function imprimirTicket(pedido) {
  try {
    const resultado = await window.imprimirPedido(pedido);
    alert(resultado.mensaje);
  } catch (error) {
    alert(error.message);
  }
}
// Desde tu botón: imprimirTicket(pedidoActual)
```

Funciones disponibles:

| Función JavaScript | Acción |
| --- | --- |
| `seleccionarTicketera()` | Abre configuración y responde al volver |
| `estadoTicketera()` | Informa la selección guardada y el permiso Bluetooth |
| `imprimirPrueba()` | Envía un ticket de prueba |
| `imprimirPedido(pedido)` | Imprime tu JSON de pedido |

El puente subyacente acepta:

```javascript
window.MajoPrinter.postMessage(JSON.stringify({
  requestId: "solicitud-unica-6707",
  accion: "imprimir",
  pedido: tuObjetoPedido
}));
```

Responde por `MajoPrinter.onmessage` con `requestId`, `ok`, `codigo`, `mensaje`,
`impresora`, `seleccionada` y `permiso`. El JS incluido maneja la correlación,
los errores y el tiempo de espera. También se acepta el JSON del pedido sin
envoltorio, aunque el envoltorio es preferible para asociar la respuesta.

El puente solo admite el frame principal en HTTPS de majocafeteria.com o
www.majocafeteria.com. Si tu botón está en un iframe de boletas, envía el objeto
al padre con postMessage y haz que el padre llame a imprimirPedido, comprobando
`event.origin` y `event.source`. No se autoriza el origen comodín '*'. En Chrome
no existe este puente. Si Android System WebView es antiguo, la app avisa que
hay que actualizarlo; no usa un puente inseguro alternativo.

## Boleta de 58 mm: diseño nuevo

`imprimirPedido(pedido)` ahora imprime una imagen de 384 puntos con logo,
fuente monospace de 32 columnas, guiones separados, productos en dos líneas,
importes alineados, total en negrita, monto en letras y pie de agradecimiento.
Mantiene el total enviado por el servidor y las unidades con precios modificados.
384 es un ancho configurado que funcionó en la prueba del usuario; no se detecta.
Los datos del negocio están en ReceiptLayout.kt y el tamaño en ReceiptImage.kt.
El logo usa el vector majo4 existente, en negro; no reproduce el logotipo completo
azul de la foto. No hay margen horizontal añadido al texto.

El JSON anterior sigue funcionando. Campos adicionales opcionales:

- boleta_fecha_emision, boleta_hora_emision: fecha y hora de emisión.
- boleta_cliente_direccion (o cliente_direccion): dirección del cliente.
- op_gravadas, igv: importes oficiales del documento, como cadenas decimales.
- precios_incluyen_impuestos: true para mostrar esa leyenda.
- total_letras: texto del servidor; si falta se convierte el total a letras.
- boleta_url: URL real de consulta para generar el QR.
- qr_texto: contenido del QR provisto por tu sistema; tiene prioridad sobre URL.
- es_prueba: true identifica el documento como PRUEBA - SIN VALOR FISCAL.

Si no llega impuesto o QR, se omite esa parte, sin inferir impuestos del total
ni usar boleta_document_id como si fuera una URL. Sin número de boleta se imprime
TICKET DE PEDIDO y no la leyenda de representación de boleta electrónica.
Esto no emite ni valida una boleta; solo imprime los datos recibidos.

`web/sidebar.blade.php` contiene TODO el JS inline y el botón Probar Boleta 58 mm.
Reemplaza el sidebar que incluye tu web y no cargues majo-printer.js por separado.
Los botones de prueba siguen visibles solo para el rol develop.
La prueba usa los productos y los importes de la foto, marcada SIN VALOR FISCAL.
Su QR apunta a la página principal de Majo como demostración; los pedidos reales
necesitan su boleta_url o qr_texto. El archivo de ejemplo está en
app/src/main/assets/boleta-diseno-ejemplo.json.

Se conserva Probar Impresión (texto) y Probar Logo y Cursiva (imagen pequeña).
El diseño completo se prueba con Probar Boleta 58 mm.
Se usa Bluetooth clásico SPP/RFCOMM y raster ESC/POS, sin corte automático.
El envío se divide en bloques pequeños con pausa para el búfer de la ticketera.
El socket tiene 50 segundos de límite y el JS espera 60 segundos.
No se reintenta automáticamente: revisa el papel si un envío falla parcialmente.
Se admiten 100 productos, mensajes de 128 KiB y hasta 24000 filas de imagen.
La app conserva WebView, sesiones, splash, recarga, alertas y vista previa PDF.
El diseño gráfico no requiere generar un PDF previamente.

## Validación

Se verificaron XML, paquetes, referencias de recursos y catálogo Gradle.
Se añadieron pruebas del layout, columnas, impuestos/QR opcionales y monto en letras. Se
prueba la comunicación JavaScript con respuestas correlacionadas, selección,
estado, impresión y errores. Las pruebas unitarias de formato están en
`app/src/test/java/com/majocafe/majoapp/ReceiptFormatterTest.kt`.

Este entorno no tiene SDK Android ni Gradle, y Java no ejecuta; no se pudo
compilar el APK ni ejecutar los tests Kotlin. Ejecuta en Windows:

```text
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

Luego prueba en teléfono: seleccionar/guardar, prueba y pedido, permiso denegado,
Bluetooth apagado, ticketera desconectada, botón de tu web, arranque con selección
ya guardada, splash, PDF, alertas, recarga y teclado. La mejora investigada de
FLAG_FULLSCREEN con el teclado abierto no se aplicó: se conserva el controlador
anterior que restaura las barras al cerrar el teclado.
