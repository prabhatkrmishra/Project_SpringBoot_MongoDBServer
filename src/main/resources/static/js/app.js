(function () {
    'use strict';

    // Highlight the sidebar link matching the current path
    document.querySelectorAll('.sidebar-link[href]').forEach(function (link) {
        var linkPath = link.getAttribute('href').split('#')[0].split('?')[0];
        if (!linkPath) {
            return;
        }
        var here = window.location.pathname;
        var isMatch = linkPath === '/' ? here === '/' : here === linkPath || here.indexOf(linkPath + '/') === 0;
        if (isMatch) {
            link.classList.add('active');
            link.setAttribute('aria-current', 'page');
        }
    });

    // Copy-to-clipboard buttons (data-copy attribute holds the text)
    document.querySelectorAll('.copy-btn').forEach(function (button) {
        button.addEventListener('click', function () {
            var text = button.getAttribute('data-copy');
            if (!text) {
                return;
            }
            var done = function () {
                var original = button.textContent;
                button.textContent = 'Copied!';
                setTimeout(function () {
                    button.textContent = original;
                }, 1500);
            };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text).then(done, function () {
                    fallbackCopy(text, done);
                });
            } else {
                fallbackCopy(text, done);
            }
        });
    });

    function fallbackCopy(text, done) {
        var textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
        } catch (e) {
            /* clipboard unavailable - ignore */
        }
        document.body.removeChild(textarea);
        done();
    }

    // Auto-dismiss flash messages after a few seconds
    document.querySelectorAll('.flash').forEach(function (flash) {
        setTimeout(function () {
            flash.classList.add('fade-out');
            setTimeout(function () {
                if (flash.parentNode) {
                    flash.parentNode.removeChild(flash);
                }
            }, 600);
        }, 6000);
    });

    // Type-to-confirm destructive actions
    document.querySelectorAll('input[data-confirm-target]').forEach(function (input) {
        var target = input.getAttribute('data-confirm-target');
        var form = input.closest('form');
        var submit = form ? form.querySelector('button[type="submit"]') : null;
        if (!submit) {
            return;
        }
        input.addEventListener('input', function () {
            submit.disabled = input.value.trim() !== target;
        });
    });

    // Password visibility toggles (data-toggle-password targets an input id)
    document.querySelectorAll('button[data-toggle-password]').forEach(function (toggle) {
        toggle.addEventListener('click', function () {
            var input = document.getElementById(toggle.getAttribute('data-toggle-password'));
            if (!input) {
                return;
            }
            var show = input.type === 'password';
            input.type = show ? 'text' : 'password';
            toggle.textContent = show ? 'Hide' : 'Show';
        });
    });

    // Confirm dialogs for inline destructive forms (data-confirm holds the message)
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
        form.addEventListener('submit', function (event) {
            var message = form.getAttribute('data-confirm');
            if (message && !window.confirm(message)) {
                event.preventDefault();
            }
        });
    });

    // Prevent double submission: lock the submit button while the request is in
    // flight. Provisioning is not idempotent - two rapid clicks would otherwise
    // race and one would fail with a 409.
    document.querySelectorAll('form').forEach(function (form) {
        var submit = form.querySelector('button[type="submit"]');
        if (!submit || submit.hasAttribute('data-no-lock')) {
            return;
        }
        form.addEventListener('submit', function (event) {
            if (event.defaultPrevented) {
                return; // a confirm dialog was canceled - keep the button usable
            }
            if (submit.disabled) {
                return;
            }
            submit.dataset.originalText = submit.dataset.originalText || submit.textContent;
            submit.disabled = true;
            submit.textContent = 'Working…';
        });
    });

    // Client-side filter for tables (data-filter-table targets a table selector)
    var filterInput = document.querySelector('input[data-filter-table]');
    if (filterInput) {
        var table = document.querySelector(filterInput.getAttribute('data-filter-table'));
        if (table) {
            filterInput.addEventListener('input', function () {
                var query = filterInput.value.trim().toLowerCase();
                table.querySelectorAll('tbody tr').forEach(function (row) {
                    var matches = row.textContent.toLowerCase().indexOf(query) !== -1;
                    row.style.display = matches ? '' : 'none';
                });
            });
        }
    }
})();
