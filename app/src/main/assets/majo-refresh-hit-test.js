/**
 * Hit-test ligero para el gesto de recarga de MAJO.
 * Se consulta una sola vez al inicio de cada gesto candidato.
 * Devuelve "root" (página principal), "overlay" (modal/sidebar) o "deny".
 * Los iframes se bloquean: no se conoce con seguridad su scroll interno.
 *
 * __TOUCH_X__ / __TOUCH_Y__: coordenadas relativas dentro del WebView [0, 1].
 * __ROOT_SCROLLED_ON_DOWN__: posición vertical nativa registrada en ACTION_DOWN.
 */
(function () {
    try {
        if (window !== window.top || !document.documentElement) return 'deny';

        var viewport = window.visualViewport;
        if (viewport && viewport.scale > 1.05) return 'deny';

        var x = __TOUCH_X__ * (viewport ? viewport.width : window.innerWidth);
        var y = __TOUCH_Y__ * (viewport ? viewport.height : window.innerHeight);
        var hit = document.elementFromPoint(x, y);
        if (!hit) return 'deny';

        // En Shadow DOM abierto, encontrar el elemento realmente tocado.
        for (var depth = 0; depth < 12 && hit.shadowRoot; depth++) {
            var inner = hit.shadowRoot.elementFromPoint(x, y);
            if (!inner || inner === hit) break;
            hit = inner;
        }

        var overlay = null;
        var overlaySelector = '.modal, .offcanvas, .sidebar, .sidenav, .side-nav, ' +
            '.drawer, .side-menu, .sidemenu, .menu-lateral, ' +
            '[id*="sidebar" i], [role="dialog"], [aria-modal="true"], dialog, ' +
            '[data-majo-refresh-scope]';
        var verticalScrollable = /^(auto|scroll|overlay|hidden)$/;
        var horizontalScrollable = /^(auto|scroll|overlay)$/;

        // Solo inspeccionar la cadena de ancestros que participa en el gesto.
        for (var node = hit, visited = 0; node && visited < 80; visited++) {
            if (node.nodeType === 1) {
                var name = node.localName || '';
                // No forzar recargas dentro de iframes o controles con gestos propios.
                if (node.matches('iframe, frame, object, embed, input, textarea, select, ' +
                    '[contenteditable], .modal-backdrop, .offcanvas-backdrop')) return 'deny';

                // No asumir el scroll interno de Shadow DOM cerrado.
                if (name.indexOf('-') !== -1 && !node.shadowRoot) return 'deny';

                var css = getComputedStyle(node);
                // Los carruseles/filas horizontales mantienen su propio gesto.
                if (node.scrollWidth > node.clientWidth + 1 &&
                    horizontalScrollable.test(css.overflowX)) return 'deny';

                // Si ESTE contenedor todavía puede subir, NO recargar.
                // La decisión se toma una sola vez, nunca al llegar arriba.
                if (node.scrollTop > 1 &&
                    node.scrollHeight > node.clientHeight + 1 &&
                    verticalScrollable.test(css.overflowY)) return 'deny';

                // El modal/sidebar es un contexto aislado: ignoramos el scroll
                // de la página detrás de él, que puede estar bloqueada o desplazada.
                if (node.matches(overlaySelector)) {
                    overlay = node;
                    break;
                }
            }
            var parent = node.parentElement;
            if (!parent && node.getRootNode) {
                var nodeRoot = node.getRootNode();
                parent = nodeRoot && nodeRoot.host;
            }
            node = parent;
        }

        // Solo importa la cadena tocada, ya comprobada arriba. Un cuerpo o
        // panel hermano desplazado no recibe este gesto y no debe bloquearlo.
        if (overlay) return 'overlay';

        // Fuera de un overlay, la raíz es el scroll activo. El dato nativo
        // evita habilitar el gesto si se llegó arriba entre ACTION_DOWN y la
        // respuesta asíncrona de evaluateJavascript().
        if (__ROOT_SCROLLED_ON_DOWN__) return 'deny';
        var root = document.scrollingElement || document.documentElement;
        if (root.scrollTop > 1 || window.scrollY > 1) return 'deny';
        return 'root';
    } catch (_) {
        return 'deny'; // Si no se puede clasificar, nunca recargar.
    }
})()
