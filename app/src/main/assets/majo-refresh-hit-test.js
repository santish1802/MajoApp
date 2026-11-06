/**
 * Consulta puntual (una vez por gesto candidato) desde WebView.evaluateJavascript.
 * __TOUCH_X__ y __TOUCH_Y__ son fracciones [0,1] del WebView visible.
 * Devuelve true SOLO cuando el gesto puede empezar un pull-to-refresh.
 * Nunca se instalan listeners, timers ni se accede al contenido de iframes.
 */
(function () {
    try {
        if (window !== window.top || !document.documentElement) return false;

        var root = document.scrollingElement || document.documentElement;
        if (root.scrollTop > 1 || window.scrollY > 1) return false;

        var viewport = window.visualViewport;
        // No competir con arrastres de una página ampliada.
        if (viewport && viewport.scale > 1.05) return false;

        var x = __TOUCH_X__ * (viewport ? viewport.width : window.innerWidth);
        var y = __TOUCH_Y__ * (viewport ? viewport.height : window.innerHeight);
        var hit = document.elementFromPoint(x, y);
        if (!hit) return false;

        // Si el elemento está en un Shadow DOM abierto, busca el más profundo.
        // Si es un componente de Shadow DOM cerrado, no es inspeccionable;
        // se bloquea como precaución más abajo.
        for (var depth = 0; depth < 12 && hit.shadowRoot; depth++) {
            var inner = hit.shadowRoot.elementFromPoint(x, y);
            if (!inner || inner === hit) break;
            hit = inner;
        }

        for (var node = hit, visited = 0; node && visited < 80; visited++) {
            if (node.nodeType === 1) {
                var name = node.localName || '';
                // No ingresar al iframe: su contenido puede ser de otro origen.
                // También protege elementos interactivos con gestos propios.
                if (node.matches('iframe, frame, object, embed, input, textarea, select, ' +
                    '[contenteditable], [role="dialog"], [aria-modal="true"], ' +
                    'dialog, .modal, .offcanvas')) return false;

                // Conservador con Web Components: un shadow root cerrado puede
                // contener scroll que no es visible desde la página principal.
                if (name.indexOf('-') !== -1 && !node.shadowRoot) return false;

                if (node !== root) {
                    var css = getComputedStyle(node);
                    var scroller = /^(auto|scroll|overlay|hidden)$/;
                    if (node.scrollHeight > node.clientHeight + 1 &&
                        scroller.test(css.overflowY)) return false;
                    if (node.scrollWidth > node.clientWidth + 1 &&
                        scroller.test(css.overflowX)) return false;
                }
            }
            var parent = node.parentElement;
            if (!parent && node.getRootNode) {
                var nodeRoot = node.getRootNode();
                parent = nodeRoot && nodeRoot.host;
            }
            node = parent;
        }
        return true;
    } catch (_) {
        return false; // Nunca recargar por una comprobación incompleta.
    }
})()
