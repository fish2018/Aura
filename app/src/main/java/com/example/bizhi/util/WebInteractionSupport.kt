package com.example.bizhi.util

import android.webkit.ValueCallback
import android.webkit.WebView

object WebInteractionSupport {

    fun applyCompatibilityFixes(
        webView: WebView,
        callback: ValueCallback<String>? = null
    ) {
        val script = """
            (function() {
              try {
                var doc = document;
                var root = doc && doc.documentElement;
                var body = doc && doc.body;
                if (!doc || !root || !body) {
                  return 'not-ready';
                }

                var overlayId = '__aura_select_overlay__';
                var styleId = '__aura_interaction_style__';

                var closeOverlay = function() {
                  var existing = doc.getElementById(overlayId);
                  if (existing && existing.parentNode) {
                    existing.parentNode.removeChild(existing);
                  }
                };

                var isVisibleSelect = function(select) {
                  if (!select || select.disabled || select.multiple || (select.size && select.size > 1)) {
                    return false;
                  }
                  var rect = select.getBoundingClientRect();
                  if (!rect || rect.width <= 0 || rect.height <= 0) {
                    return false;
                  }
                  var style = window.getComputedStyle(select);
                  if (!style) {
                    return true;
                  }
                  return style.display !== 'none' &&
                    style.visibility !== 'hidden' &&
                    style.pointerEvents !== 'none' &&
                    style.opacity !== '0';
                };

                var emitSelectEvents = function(select, previousValue) {
                  if (!select) {
                    return;
                  }
                  if (select.value !== previousValue) {
                    select.dispatchEvent(new Event('input', { bubbles: true }));
                    select.dispatchEvent(new Event('change', { bubbles: true }));
                  }
                  select.dispatchEvent(new Event('blur', { bubbles: false }));
                };

                var createOptionButton = function(select, option, previousValue) {
                  var button = doc.createElement('button');
                  button.type = 'button';
                  button.textContent = option.textContent || option.label || option.value || ' '; 
                  button.style.display = 'block';
                  button.style.width = '100%';
                  button.style.border = '0';
                  button.style.borderRadius = '12px';
                  button.style.margin = '0';
                  button.style.padding = '14px 16px';
                  button.style.background = option.value === select.value ? '#f4ece8' : 'transparent';
                  button.style.color = option.value === select.value ? '#8B1D1D' : '#222';
                  button.style.fontSize = '16px';
                  button.style.fontWeight = option.value === select.value ? '600' : '400';
                  button.style.lineHeight = '1.3';
                  button.style.textAlign = 'left';
                  button.style.cursor = 'pointer';
                  button.style.outline = 'none';
                  button.addEventListener('click', function(event) {
                    event.preventDefault();
                    event.stopPropagation();
                    try {
                      select.focus();
                    } catch (error) {
                    }
                    select.value = option.value;
                    emitSelectEvents(select, previousValue);
                    closeOverlay();
                  });
                  return button;
                };

                var openOverlay = function(select) {
                  if (!isVisibleSelect(select)) {
                    return;
                  }
                  closeOverlay();
                  var options = Array.prototype.filter.call(select.options || [], function(option) {
                    return option && !option.disabled;
                  });
                  if (!options.length) {
                    return;
                  }

                  var previousValue = select.value;
                  var overlay = doc.createElement('div');
                  overlay.id = overlayId;
                  overlay.style.position = 'fixed';
                  overlay.style.left = '0';
                  overlay.style.top = '0';
                  overlay.style.right = '0';
                  overlay.style.bottom = '0';
                  overlay.style.zIndex = '2147483647';
                  overlay.style.background = 'rgba(0, 0, 0, 0.34)';
                  overlay.style.display = 'flex';
                  overlay.style.alignItems = 'flex-end';
                  overlay.style.justifyContent = 'center';
                  overlay.style.paddingTop = '28px';
                  overlay.style.paddingRight = '16px';
                  overlay.style.paddingBottom = '132px';
                  overlay.style.paddingLeft = '16px';
                  overlay.style.boxSizing = 'border-box';
                  overlay.style.webkitTapHighlightColor = 'transparent';

                  var panel = doc.createElement('div');
                  panel.style.width = 'min(720px, 100%)';
                  panel.style.maxHeight = 'min(60vh, calc(100vh - 184px))';
                  panel.style.overflowY = 'auto';
                  panel.style.background = '#ffffff';
                  panel.style.borderRadius = '18px';
                  panel.style.boxShadow = '0 16px 48px rgba(0, 0, 0, 0.24)';
                  panel.style.padding = '10px';
                  panel.style.boxSizing = 'border-box';
                  panel.style.webkitOverflowScrolling = 'touch';

                  var title = doc.createElement('div');
                  title.textContent = select.getAttribute('aria-label') || select.name || select.id || '请选择';
                  title.style.padding = '6px 8px 12px 8px';
                  title.style.color = '#666';
                  title.style.fontSize = '13px';
                  title.style.fontWeight = '600';
                  panel.appendChild(title);

                  options.forEach(function(option) {
                    panel.appendChild(createOptionButton(select, option, previousValue));
                  });

                  overlay.addEventListener('click', function(event) {
                    if (event.target === overlay) {
                      event.preventDefault();
                      closeOverlay();
                    }
                  });

                  panel.addEventListener('click', function(event) {
                    event.stopPropagation();
                  });

                  overlay.appendChild(panel);
                  body.appendChild(overlay);
                };

                var openHandler = function(event) {
                  var select = event.currentTarget;
                  if (!isVisibleSelect(select)) {
                    return;
                  }
                  event.preventDefault();
                  event.stopPropagation();
                  openOverlay(select);
                };

                var enhanceSelect = function(select) {
                  if (!select || select.__auraSelectPatched) {
                    return;
                  }
                  select.__auraSelectPatched = true;
                  select.addEventListener('pointerdown', openHandler, true);
                  select.addEventListener('mousedown', openHandler, true);
                  select.addEventListener('touchstart', openHandler, true);
                  select.addEventListener('click', openHandler, true);
                };

                var enhanceTree = function(node) {
                  if (!node) {
                    return;
                  }
                  if (node.tagName && node.tagName.toLowerCase() === 'select') {
                    enhanceSelect(node);
                    return;
                  }
                  if (!node.querySelectorAll) {
                    return;
                  }
                  Array.prototype.forEach.call(node.querySelectorAll('select'), function(select) {
                    enhanceSelect(select);
                  });
                };

                var style = doc.getElementById(styleId);
                if (!style) {
                  style = doc.createElement('style');
                  style.id = styleId;
                  style.textContent = [
                    'a, button, [role="button"], input, select, textarea, summary, label {',
                    '  touch-action: manipulation !important;',
                    '  -webkit-tap-highlight-color: rgba(0, 0, 0, 0.12);',
                    '}',
                    'select { cursor: pointer !important; }',
                    'button:focus, button:focus-visible, a:focus, a:focus-visible,',
                    '[role="button"]:focus, [role="button"]:focus-visible,',
                    'select:focus, select:focus-visible, input:focus, input:focus-visible {',
                    '  outline: none !important;',
                    '  box-shadow: none !important;',
                    '}'
                  ].join('\n');
                  root.appendChild(style);
                }

                enhanceTree(doc);

                if (window.__auraInteractionObserver) {
                  window.__auraInteractionObserver.disconnect();
                }
                var observer = new MutationObserver(function(mutations) {
                  mutations.forEach(function(mutation) {
                    Array.prototype.forEach.call(mutation.addedNodes || [], function(node) {
                      enhanceTree(node);
                    });
                  });
                });
                observer.observe(root, { childList: true, subtree: true });
                window.__auraInteractionObserver = observer;
                window.__auraInteractionCompat = {
                  refresh: function() {
                    enhanceTree(doc);
                    return 'refreshed';
                  },
                  closeSelect: closeOverlay
                };

                return 'installed';
              } catch (error) {
                return 'error:' + error;
              }
            })();
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(script, callback)
        }
    }

    fun requestTouchFocus(webView: WebView) {
        webView.post {
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            if (!webView.hasFocus()) {
                webView.requestFocus()
            }
        }
    }

}
